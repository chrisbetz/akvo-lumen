(ns akvo.lumen.lib.aggregation.line
  (:require [akvo.lumen.lib :as lib]
            [akvo.lumen.lib.aggregation.filter :as filter]
            [akvo.lumen.lib.aggregation.utils :as utils]
            [clojure.java.jdbc :as jdbc]))

(defn- run-query [tenant-conn table-name sql-text column-x-name column-y-name filter-sql aggregation-method]
  (rest (jdbc/query tenant-conn
                    [(format sql-text
                             column-x-name column-y-name table-name filter-sql aggregation-method)]
                    {:as-arrays? true})))

(defn query
  [tenant-conn {:keys [columns table-name]} query]
  (let [filter-sql (filter/sql-str columns (get query "filters"))
        column-x (utils/find-column columns (get query "metricColumnX"))
        column-x-name (get column-x "columnName")
        column-x-title (get column-x "title")
        column-y (utils/find-column columns (get query "metricColumnY"))
        column-y-name (get column-y "columnName")
        column-y-title (get column-y "title")
        aggregation-method (get query "metricAggregation")
        sql-text-with-aggregation "SELECT %1$s, %5$s(%2$s) FROM %3$s WHERE %4$s GROUP BY %1$s ORDER BY %1$s"
        sql-text-without-aggreagtion "SELECT %1$s, %2$s FROM %3$s WHERE %4$s ORDER BY $%1$s"
        sql-text (if aggregation-method sql-text-with-aggregation sql-text-without-aggreagtion)
        sql-response (run-query tenant-conn table-name sql-text column-x-name column-y-name filter-sql aggregation-method)]
    (lib/ok
     {"series" [{"key" column-y-title
                 "label" column-y-title
                 "data" (mapv (fn [[x-value y-value]]
                                {"value" y-value})
                              sql-response)}]
      "common" {"data" (mapv (fn [[x-value y-value]]
                               {"timestamp" x-value})
                             sql-response)}})))

