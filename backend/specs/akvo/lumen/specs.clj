(ns akvo.lumen.specs
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [integrant.core :as ig]
            [clojure.string :as string]
            [akvo.lumen.util :refer (squuid)]
            [clojure.tools.logging :as log]))

(defn keyname [key] (str (namespace key) "/" (name key)))

(defn sample-with-gen* [s map-gen]
  (last (map first (s/exercise s 10 (reduce-kv (fn [c k v]
                                                  (if (fn? v)
                                                    (assoc c k v)
                                                    (assoc c k #(s/gen #{v})))
                                                  ) {} map-gen)))))


(defn sample-with-gen [s map-gen amount]
  (map first (s/exercise s amount map-gen)))

(defn sample
  ([s]
   (sample s 1))
  ([s amount]
   (let [res (map first (s/exercise s amount))]
     (if (== 1 amount)
       (first res)
       res))))

(defn str-uuid? [v]
  (when (some? v)
    (try
      (uuid? (read-string (format "#uuid \"%s\"" v)))
      (catch Throwable t false))))

(s/def ::str-uuid
  (s/with-gen
    str-uuid?
    #(s/gen (reduce (fn [c _] (conj c (str (squuid)))) #{} (range 100)))))

(def str-uuid-gen #(s/gen (reduce (fn [c _] (conj c (str (squuid)))) #{} (range 100))))
(def sort? #{"ASC" "DESC"})
(s/def ::sort sort?)

(defn sample-with-filter
  "generates a sample satisfying a filter condition."
  ([spec filter-fun]
   (sample-with-filter spec filter-fun 20))
  ([spec filter-fun attempts]
   (loop [attempt 0]
     (let [res (filter filter-fun (sample spec attempts))]
       (if (and (empty? res) (< attempt attempts))
         (recur (inc attempt))
         (first res))))))

(defmethod ig/init-key :akvo.lumen.specs/specs [_ opts]
  opts)

(s/def ::conform-specs boolean?)
(defmethod ig/pre-init-spec :akvo.lumen.specs/specs [_]
  (s/keys :req-un [::conform-specs]))

(s/def ::date-number (s/nilable number?))

(s/def ::non-empty-string
  (s/with-gen
    (s/and string? (complement string/blank?))
    #(gen/not-empty (gen/string-alphanumeric))))
