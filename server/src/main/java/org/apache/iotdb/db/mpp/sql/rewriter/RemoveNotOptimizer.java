/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iotdb.db.mpp.sql.rewriter;

import org.apache.iotdb.db.exception.sql.StatementAnalyzeException;
import org.apache.iotdb.db.metadata.path.PartialPath;
import org.apache.iotdb.db.mpp.common.filter.FunctionFilter;
import org.apache.iotdb.db.mpp.common.filter.QueryFilter;
import org.apache.iotdb.db.mpp.sql.constant.FilterConstant;
import org.apache.iotdb.db.mpp.sql.constant.FilterConstant.FilterType;

import java.util.List;
import java.util.Set;

public class RemoveNotOptimizer implements IFilterOptimizer {

  /**
   * get DNF(disjunctive normal form) for this filter tree. Before getDNF, this op tree must be
   * binary, in another word, each non-leaf node has exactly two children.
   *
   * @return optimized filter
   * @throws StatementAnalyzeException exception in RemoveNot optimizing
   */
  @Override
  public QueryFilter optimize(QueryFilter filter) throws StatementAnalyzeException {
    Set<PartialPath> pathSet = filter.getPathSet();
    QueryFilter optimizedFilter = removeNot(filter);
    optimizedFilter.setPathSet(pathSet);
    return optimizedFilter;
  }

  private QueryFilter removeNot(QueryFilter filter) throws StatementAnalyzeException {
    if (filter.isLeaf()) {
      return filter;
    }
    FilterType filterType = filter.getFilterType();
    switch (filterType) {
      case KW_AND:
      case KW_OR:
        // replace children in-place for efficiency
        List<QueryFilter> children = filter.getChildren();
        if (children.size() < 2) {
          throw new StatementAnalyzeException(
              "Filter has some time series don't correspond to any known time series");
        }
        children.set(0, removeNot(children.get(0)));
        children.set(1, removeNot(children.get(1)));
        return filter;
      case KW_NOT:
        if (filter.getChildren().size() < 1) {
          throw new StatementAnalyzeException(
              "Filter has some time series don't correspond to any known time series");
        }
        return reverseFilter(filter.getChildren().get(0));
      default:
        throw new StatementAnalyzeException("removeNot", filterType);
    }
  }

  /**
   * reverse given filter to reversed expression.
   *
   * @param filter BasicFunctionFilter
   * @return QueryFilter reversed BasicFunctionFilter
   * @throws StatementAnalyzeException exception in reverse filter
   */
  private QueryFilter reverseFilter(QueryFilter filter) throws StatementAnalyzeException {
    FilterType filterType = filter.getFilterType();
    if (filter.isLeaf()) {
      ((FunctionFilter) filter).reverseFunc();
      return filter;
    }
    switch (filterType) {
      case KW_AND:
      case KW_OR:
        List<QueryFilter> children = filter.getChildren();
        children.set(0, reverseFilter(children.get(0)));
        children.set(1, reverseFilter(children.get(1)));
        filter.setFilterType(FilterConstant.filterReverseWords.get(filterType));
        return filter;
      case KW_NOT:
        return removeNot(filter.getChildren().get(0));
      default:
        throw new StatementAnalyzeException("reverseFilter", filterType);
    }
  }
}
