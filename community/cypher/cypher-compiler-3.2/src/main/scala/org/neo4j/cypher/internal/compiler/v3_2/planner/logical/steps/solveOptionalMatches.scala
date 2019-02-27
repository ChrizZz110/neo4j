/*
 * Copyright (c) 2002-2019 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.compiler.v3_2.planner.logical.steps

import org.neo4j.cypher.internal.compiler.v3_2.planner.logical.plans.LogicalPlan
import org.neo4j.cypher.internal.compiler.v3_2.planner.logical.steps.solveOptionalMatches.OptionalSolver
import org.neo4j.cypher.internal.compiler.v3_2.planner.logical.{LogicalPlanningContext, LogicalPlanningFunction2}
import org.neo4j.cypher.internal.ir.v3_2.{IdName, QueryGraph}

object solveOptionalMatches {
  type OptionalSolver = LogicalPlanningFunction2[QueryGraph, LogicalPlan, Option[LogicalPlan]]
}

case object applyOptional extends OptionalSolver {
  def apply(optionalQg: QueryGraph, lhs: LogicalPlan)(implicit context: LogicalPlanningContext) = {
    val innerContext: LogicalPlanningContext = context.recurse(lhs)
    val inner = context.strategy.plan(optionalQg)(innerContext)
    val rhs = context.logicalPlanProducer.planOptional(inner, lhs.availableSymbols)(innerContext)
    Some(context.logicalPlanProducer.planApply(lhs, rhs))
  }
}

case object outerHashJoin extends OptionalSolver {
  def apply(optionalQg: QueryGraph, lhs: LogicalPlan)(implicit context: LogicalPlanningContext) = {
    val joinNodes = optionalQg.argumentIds
    val solvedHints = optionalQg.joinHints.filter { hint =>
      val hintVariables = hint.variables.map(v => IdName(v.name)).toSet
      hintVariables.subsetOf(joinNodes)
    }
    val rhs = context.strategy.plan(optionalQg.withoutArguments().withoutHints(solvedHints))

    if (joinNodes.nonEmpty &&
      joinNodes.forall(lhs.availableSymbols) &&
      joinNodes.forall(optionalQg.patternNodes)) {
      Some(context.logicalPlanProducer.planOuterHashJoin(joinNodes, lhs, rhs, solvedHints))
    } else {
      None
    }
  }
}