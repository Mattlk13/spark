/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.spark.sql.catalyst.parser

import java.util
import java.util.Locale

import scala.collection.{immutable, mutable}
import scala.util.matching.Regex

import org.antlr.v4.runtime.{ParserRuleContext, Token}
import org.antlr.v4.runtime.misc.Interval
import org.antlr.v4.runtime.tree.{ParseTree, TerminalNodeImpl}

import org.apache.spark.SparkException
import org.apache.spark.sql.catalyst.analysis.UnresolvedIdentifier
import org.apache.spark.sql.catalyst.parser.SqlBaseParser.{BeginLabelContext, EndLabelContext, MultipartIdentifierContext}
import org.apache.spark.sql.catalyst.plans.logical.{CreateVariable, ErrorCondition}
import org.apache.spark.sql.catalyst.trees.CurrentOrigin
import org.apache.spark.sql.catalyst.util.SparkParserUtils
import org.apache.spark.sql.catalyst.util.SparkParserUtils.withOrigin
import org.apache.spark.sql.errors.{QueryParsingErrors, SqlScriptingErrors}

/**
 * A collection of utility methods for use during the parsing process.
 */
object ParserUtils extends SparkParserUtils {
  def operationNotAllowed(message: String, ctx: ParserRuleContext): Nothing = {
    throw QueryParsingErrors.operationNotAllowedError(message, ctx)
  }

  def invalidStatement(statement: String, ctx: ParserRuleContext): Nothing = {
    throw QueryParsingErrors.invalidStatementError(statement, ctx)
  }

  def checkDuplicateClauses[T](
      nodes: util.List[T], clauseName: String, ctx: ParserRuleContext): Unit = {
    if (nodes.size() > 1) {
      throw QueryParsingErrors.duplicateClausesError(clauseName, ctx)
    }
  }

  /** Check if duplicate keys exist in a set of key-value pairs. */
  def checkDuplicateKeys[T](keyPairs: Seq[(String, T)], ctx: ParserRuleContext): Unit = {
    keyPairs.groupBy(_._1).filter(_._2.size > 1).foreach { case (key, _) =>
      throw QueryParsingErrors.duplicateKeysError(key, ctx)
    }
  }

  /** Get all the text which comes after the given rule. */
  def remainder(ctx: ParserRuleContext): String = remainder(ctx.getStop)

  /** Get all the text which comes after the given token. */
  def remainder(token: Token): String = {
    val stream = token.getInputStream
    val interval = Interval.of(token.getStopIndex + 1, stream.size() - 1)
    stream.getText(interval)
  }

  /**
   * Get all the text which between the given start and end tokens.
   * When we need to extract everything between two tokens including all spaces we should use
   * this method instead of defined a named Antlr4 rule for .*?,
   * which somehow parse "a b" -> "ab" in some cases
   */
  def interval(start: Token, end: Token): String = {
    val interval = Interval.of(start.getStopIndex + 1, end.getStartIndex - 1)
    start.getInputStream.getText(interval)
  }

  /** Convert a string node into a string without unescaping. */
  def stringWithoutUnescape(node: Token): String = {
    // STRING parser rule forces that the input always has quotes at the starting and ending.
    node.getText.slice(1, node.getText.length - 1)
  }

  /** Collect the entries if any. */
  def entry(key: String, value: Token): Seq[(String, String)] = {
    Option(value).toSeq.map(x => key -> string(x))
  }

  /** Validate the condition. If it doesn't throw a parse exception. */
  def validate(f: => Boolean, message: String, ctx: ParserRuleContext): Unit = {
    if (!f) {
      throw new ParseException(
        errorClass = "_LEGACY_ERROR_TEMP_0064",
        messageParameters = Map("msg" -> message),
        ctx)
    }
  }

  /** the column name pattern in quoted regex without qualifier */
  val escapedIdentifier = "`((?s).+)`".r

  /** the column name pattern in quoted regex with qualifier */
  val qualifiedEscapedIdentifier = ("((?s).+)" + """.""" + "`((?s).+)`").r

  /**
   * Normalizes the expression parser tree to a SQL string which will be used to generate
   * the expression alias. In particular, it concatenates terminal nodes of the tree and
   * upper casts keywords and numeric literals.
   */
  def toExprAlias(ctx: ParseTree): String = {
    val sb = new StringBuilder()
    def concatTerms(ctx: ParseTree): Unit = {
      for (i <- 0 until ctx.getChildCount) {
        ctx.getChild(i) match {
          case term: TerminalNodeImpl =>
            val termText = term.getText
            val tt = term.getSymbol.getType
            val current = if ((SqlBaseParser.ADD <= tt && tt <= SqlBaseParser.ZONE) ||
              (SqlBaseParser.BIGINT_LITERAL <= tt && tt <= SqlBaseParser.BIGDECIMAL_LITERAL)) {
              termText.toUpperCase(Locale.ROOT)
            } else {
              termText
            }
            sb.append(current)
          case child => concatTerms(child)
        }
      }
    }
    concatTerms(ctx)
    sb.toString()
  }
}

class CompoundBodyParsingContext {

  object State extends Enumeration {
    type State = Value
    val INIT, VARIABLE, CONDITION, HANDLER, STATEMENT = Value
  }

  private var currentState: State.State = State.INIT

  /** Transition to VARIABLE state. */
  def variable(createVariable: CreateVariable, allowVarDeclare: Boolean): Unit = {
    if (!allowVarDeclare) {
      throw SqlScriptingErrors.variableDeclarationNotAllowedInScope(
        createVariable.origin, createVariable.name.asInstanceOf[UnresolvedIdentifier].nameParts)
    }
    transitionTo(State.VARIABLE, createVariable = Some(createVariable), None)
  }

  /** Transition to CONDITION state. */
  def condition(errorCondition: ErrorCondition, allowConditionDeclare: Boolean): Unit = {
    if (!allowConditionDeclare) {
      throw SqlScriptingErrors.conditionDeclarationNotAtStartOfCompound(
        errorCondition.origin, errorCondition.conditionName
      )
    }
    transitionTo(State.CONDITION, None, errorCondition = Some(errorCondition))
  }

  /** Transition to HANDLER state. */
  def handler(): Unit = {
    transitionTo(State.HANDLER)
  }

  /** Transition to STATEMENT state. */
  def statement(): Unit = {
    transitionTo(State.STATEMENT)
  }

  /**
   * Helper method to transition to a new state.
   * Possible states are:
   * 1a. VARIABLE (1)
   * 1b. CONDITION (1)
   * 2. HANDLERS (2)
   * 3. STATEMENTS (3)
   * Transition is allowed from state with number n to state with number m,
   * where m >= n.
   *
   * @param newState The new state to transition to.
   */
  private def transitionTo(
      newState: State.State,
      createVariable: Option[CreateVariable] = None,
      errorCondition: Option[ErrorCondition] = None): Unit = {
    (currentState, newState) match {
      // VALID TRANSITIONS

      case (State.INIT, _) => currentState = newState

      // Transitions from VARIABLE to other states.
      case (State.VARIABLE, State.VARIABLE) =>  // do nothing

      case (State.VARIABLE, State.CONDITION) => currentState = State.CONDITION

      case (State.VARIABLE, State.HANDLER) => currentState = State.HANDLER

      case (State.VARIABLE, State.STATEMENT) => currentState = State.STATEMENT

      // Transition from CONDITION to other states.
      case (State.CONDITION, State.CONDITION) => // do nothing

      case (State.CONDITION, State.VARIABLE) => currentState = State.VARIABLE

      case (State.CONDITION, State.HANDLER) => currentState = State.HANDLER

      case (State.CONDITION, State.STATEMENT) => currentState = State.STATEMENT

      // Transition from HANDLER to other states.
      case (State.HANDLER, State.HANDLER) => // do nothing

      case (State.HANDLER, State.STATEMENT) => currentState = State.STATEMENT

      // Transition from STATEMENT to other states.
      case (State.STATEMENT, State.STATEMENT) => // do nothing

      // INVALID TRANSITIONS

      // Invalid transitions to VARIABLE state.
      case (State.STATEMENT, State.VARIABLE) =>
        throw SqlScriptingErrors.variableDeclarationOnlyAtBeginning(
          createVariable.get.origin,
          createVariable.get.name.asInstanceOf[UnresolvedIdentifier].nameParts)

      case (State.HANDLER, State.VARIABLE) =>
        throw SqlScriptingErrors.variableDeclarationOnlyAtBeginning(
          createVariable.get.origin,
          createVariable.get.name.asInstanceOf[UnresolvedIdentifier].nameParts)

      // Invalid transitions to CONDITION state.
      case (State.STATEMENT, State.CONDITION) =>
        throw SqlScriptingErrors.conditionDeclarationNotAtStartOfCompound(
          CurrentOrigin.get,
          errorCondition.get.conditionName)

      case (State.HANDLER, State.CONDITION) =>
        throw SqlScriptingErrors.variableDeclarationOnlyAtBeginning(
          createVariable.get.origin,
          createVariable.get.name.asInstanceOf[UnresolvedIdentifier].nameParts)

      // Invalid transitions to HANDLER state.
      case (State.STATEMENT, State.HANDLER) =>
        throw SqlScriptingErrors.handlerDeclarationInWrongPlace(CurrentOrigin.get)

      // This should never happen.
      case _ =>
        throw SparkException.internalError(
          s"Invalid state transition from $currentState to $newState")
    }
  }
}

class SqlScriptingParsingContext {
  val labelContext: SqlScriptingLabelContext = new SqlScriptingLabelContext()
  val conditionContext: SqlScriptingConditionContext = new SqlScriptingConditionContext()
}

class SqlScriptingLabelContext {
  /** Set to keep track of labels seen so far */
  private val seenLabels = mutable.Set[String]()

  /**
   * Check if the beginLabelCtx and endLabelCtx match.
   * If the labels are defined, they must follow rules:
   *  - If both labels exist, they must match.
   *  - If label is qualified, it is invalid.
   *  - Begin label must exist if end label exists.
   *
   * @param beginLabelCtx Begin label context.
   * @param endLabelCtx The end label context.
   */
  private def checkLabels(
      beginLabelCtx: Option[BeginLabelContext],
      endLabelCtx: Option[EndLabelContext]) : Unit = {
    (beginLabelCtx, endLabelCtx) match {
      // Throw an error if labels do not match.
      case (Some(bl: BeginLabelContext), Some(el: EndLabelContext))
        if bl.multipartIdentifier().getText.toLowerCase(Locale.ROOT) !=
            el.multipartIdentifier().getText.toLowerCase(Locale.ROOT) =>
        withOrigin(bl) {
          throw SqlScriptingErrors.labelsMismatch(
            CurrentOrigin.get,
            bl.multipartIdentifier().getText,
            el.multipartIdentifier().getText)
        }
      // Throw an error if label is qualified.
      case (Some(bl: BeginLabelContext), _)
        if bl.multipartIdentifier().parts.size() > 1 =>
        withOrigin(bl) {
          throw SqlScriptingErrors.labelCannotBeQualified(
            CurrentOrigin.get,
            bl.multipartIdentifier().getText.toLowerCase(Locale.ROOT)
          )
        }
      // Throw an error if end label exists without begin label.
      case (None, Some(el: EndLabelContext)) =>
        withOrigin(el) {
          throw SqlScriptingErrors.endLabelWithoutBeginLabel(
            CurrentOrigin.get, el.multipartIdentifier().getText)
        }
      case _ =>
    }
  }

  /** Check if the label is defined. */
  private def isLabelDefined(beginLabelCtx: Option[BeginLabelContext]): Boolean = {
    beginLabelCtx.map(_.multipartIdentifier().getText).isDefined
  }

  /**
   * Assert the identifier is not contained within seenLabels.
   * If the identifier is contained within seenLabels, raise an exception.
   */
  private def assertIdentifierNotInSeenLabels(
      identifierCtx: Option[MultipartIdentifierContext]): Unit = {
    identifierCtx.foreach { ctx =>
      val identifierName = ctx.getText
      if (seenLabels.contains(identifierName.toLowerCase(Locale.ROOT))) {
        withOrigin(ctx) {
          throw SqlScriptingErrors
            .duplicateLabels(CurrentOrigin.get, identifierName.toLowerCase(Locale.ROOT))
        }
      }
    }
  }

  /**
   * Enter a labeled scope and return the label text.
   * If the label is defined, it will be returned and added to seenLabels.
   * If the label is not defined, a random UUID will be returned.
   */
  def enterLabeledScope(
      beginLabelCtx: Option[BeginLabelContext],
      endLabelCtx: Option[EndLabelContext]): String = {

    // Check if this label already exists in parent scopes.
    checkLabels(beginLabelCtx, endLabelCtx)

    // Get label text and add it to seenLabels.
    val labelText = if (isLabelDefined(beginLabelCtx)) {
      val txt = beginLabelCtx.get.multipartIdentifier().getText.toLowerCase(Locale.ROOT)
      if (seenLabels.contains(txt)) {
        withOrigin(beginLabelCtx.get) {
          throw SqlScriptingErrors.duplicateLabels(CurrentOrigin.get, txt)
        }
      }
      seenLabels.add(txt)
      txt
    } else {
      // Do not add the label to the seenLabels set if it is not defined.
      java.util.UUID.randomUUID.toString.toLowerCase(Locale.ROOT)
    }
    if (SqlScriptingLabelContext.isForbiddenLabelOrForVariableName(labelText)) {
      withOrigin(beginLabelCtx.get) {
        throw SqlScriptingErrors.labelOrForVariableNameForbidden(CurrentOrigin.get, labelText)
      }
    }
    labelText
  }

  /**
   * Exit a labeled scope.
   * If the label is defined, it will be removed from seenLabels.
   */
  def exitLabeledScope(beginLabelCtx: Option[BeginLabelContext]): Unit = {
    if (isLabelDefined(beginLabelCtx)) {
      seenLabels.remove(beginLabelCtx.get.multipartIdentifier().getText.toLowerCase(Locale.ROOT))
    }
  }

  /**
   * Enter a for loop scope.
   * If the for loop variable is defined, it will be asserted to not be inside seenLabels;
   * Then, if the for loop variable is defined, it will be added to seenLabels.
   */
  def enterForScope(identifierCtx: Option[MultipartIdentifierContext]): Unit = {
    identifierCtx.foreach { ctx =>
      val identifierName = ctx.getText
      assertIdentifierNotInSeenLabels(identifierCtx)
      seenLabels.add(identifierName.toLowerCase(Locale.ROOT))

      if (SqlScriptingLabelContext.isForbiddenLabelOrForVariableName(identifierName)) {
        withOrigin(ctx) {
          throw SqlScriptingErrors.labelOrForVariableNameForbidden(
            CurrentOrigin.get,
            identifierName.toLowerCase(Locale.ROOT))
        }
      }
    }
  }

  /**
   * Exit a for loop scope.
   * If the for loop variable is defined, it will be removed from seenLabels.
   */
  def exitForScope(identifierCtx: Option[MultipartIdentifierContext]): Unit = {
    identifierCtx.foreach { ctx =>
      val identifierName = ctx.getText
      seenLabels.remove(identifierName.toLowerCase(Locale.ROOT))
    }
  }

}

object SqlScriptingLabelContext {
  private val forbiddenLabelNames: immutable.Set[Regex] =
    immutable.Set("builtin".r, "session".r, "sys.*".r)

  def isForbiddenLabelOrForVariableName(labelName: String): Boolean = {
    forbiddenLabelNames.exists(_.matches(labelName.toLowerCase(Locale.ROOT)))
  }
}

class SqlScriptingConditionContext {
  private val conditionNameToSqlStateMap = mutable.HashMap[String, String]()

  def contains(conditionName: String): Boolean = conditionNameToSqlStateMap.contains(conditionName)

  def getSqlStateForCondition(conditionName: String): Option[String] =
    conditionNameToSqlStateMap.get(conditionName)

  def add(condition: ErrorCondition): Unit =
    conditionNameToSqlStateMap += condition.conditionName -> condition.sqlState

  def remove(toRemove: Iterable[String]): Unit = conditionNameToSqlStateMap --= toRemove
}
