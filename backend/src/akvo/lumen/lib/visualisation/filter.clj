(ns akvo.lumen.lib.visualisation.filter
  (:require [clojure.string :as str])
  (:import [java.sql.Timestamp]))

(defmulti filter-sql (fn [filter]
                       (get filter "strategy")))

(defn parse-number [s]
  (try
    (Double/parseDouble s)
    (catch NumberFormatException e
      (throw (ex-info "Not a number" {:string s})))))

(defn parse-date [s]
  (try
    (java.sql.Timestamp. (Long/parseLong s))
    (catch NumberFormatException e
      (throw (ex-info "Not a timestamp" {:string s})))))

(defn escape-sql-str [s]
  (str/replace s "'" "''"))

(defn comparison [op column-type column-name value]
  (condp = column-type
    "number" (format "%s %s %s"
                     column-name
                     op
                     (parse-number value))
    "date" (format "%s %s '%s'::timestamptz"
                   column-name
                   op
                   (parse-date value))
    (throw (ex-info "Invalid type" {:type column-type}))))

(defmethod filter-sql "isHigher"
  [{:strs [column operation value]}]
  (let [{column-type "type" column-name "columnName"} column
        op (if (= operation "keep") ">" "<=")]
    (comparison op column-type column-name value)))

(defmethod filter-sql "isLower"
  [{:strs [column operation value]}]
  (let [{column-type "type" column-name "columnName"} column
        op (if (= operation "keep") "<" ">=")]
    (comparison op column-type column-name value)))

(defmethod filter-sql "is"
  [{:strs [column operation value]}]
  (let [{column-type "type" column-name "columnName"} column
        op (if (= operation "keep") "=" "<>")]
    (condp = column-type
      "number" (format "%s %s %s"
                       column-name
                       op
                       (parse-number value))
      "date" (format "%s %s '%s'::timestamptz"
                     column-name
                     op
                     (parse-date value))
      "text" (format "%s %s '%s'"
                     column-name
                     op
                     (escape-sql-str value))
      (throw (ex-info "Invalid type" {:type column-type})))))

(defmethod filter-sql "isEmpty"
  [{:strs [column operation]}]
  (let [{column-type "type" column-name "columnName"} column]
    (if (= column-type "text")
      (format "coalesce(%s, '') %s ''"
              column-name
              (if (= operation "keep") "<>" "="))
      (format "%s IS %s"
              column-name
              (if (= operation "keep")
                "NULL"
                "NOT NULL")))))

(defmethod filter-sql :default [filter]
  (throw (ex-info "Invalid filter strategy" {:filter filter})))

(defn find-column [columns column-name]
  (first (filter #(= (get % "columnName") column-name) columns)))

(defn sql-str [columns filters]
  (if (empty? filters)
    "TRUE"
    (let [filters (map #(assoc % "column" (find-column columns (get % "column")))
                       filters)]
      (str/join " AND " (map filter-sql filters)))))
