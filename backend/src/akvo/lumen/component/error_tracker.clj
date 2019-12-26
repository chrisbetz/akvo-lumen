(ns akvo.lumen.component.error-tracker
  (:require [akvo.lumen.protocols :as p]
            [clojure.spec.alpha :as s]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]
            [integrant.core :as ig]
            [raven-clj.core :as raven]
            [raven-clj.interfaces :as raven-interface]
            [raven-clj.ring]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Error tracker config
;;;

(defmethod ig/pre-init-spec :akvo.lumen.component.error-tracker/config
  [_]
  map?)

(defmethod ig/init-key :akvo.lumen.component.error-tracker/config
  [_ config]
  config)

(defn blue-green?
  [server-name]
  (and (string? server-name)
       (or (= "blue" server-name)
           (= "green" server-name))))

(s/def ::dsn string?)

(s/def ::environment string?)
(s/def ::namespaces (s/coll-of string?))
(s/def ::release string?)
(s/def ::server-name blue-green?)
(s/def ::opts
  (s/keys :req-un [::namespaces]
          :opt-un [::environment ::release ::server-name]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Error tracker component
;;;

(defmethod ig/pre-init-spec :akvo.lumen.component.error-tracker/error-tracker
  [_]
  (partial satisfies? p/IErrorTracker))

(defmethod ig/init-key :akvo.lumen.component.error-tracker/error-tracker
  [_ tracker]
  tracker)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Sentry client
;;;

(defrecord SentryErrorTracker [dsn]
  p/IErrorTracker
  (track [{dsn :dsn
           {:keys [namespaces] :as opts} :opts}
          error]
    (let [event-info (event-map error opts)]
      (->> (raven-interface/stacktrace event-info error namespaces)
           (raven/capture dsn)
           future))))

(defn sentry-error-tracker [dsn]
  (SentryErrorTracker. dsn))

(defmethod ig/pre-init-spec :akvo.lumen.component.error-tracker/sentry
  [_]
  (s/keys :req-un [::dsn ::opts]))

(defmethod ig/init-key :akvo.lumen.component.error-tracker/sentry
  [_ {{:keys [dsn opts]} :tracker :as config}]
  (sentry-error-tracker dsn))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Local client (print to std out)
;;;

(defmethod ig/pre-init-spec :akvo.lumen.component.error-tracker/local
  [_]
  any?)

(defrecord LocalErrorTracker [store]
  p/IErrorTracker
  (track [this error]
    (swap! (:store this) conj (event-map error))
    (log/info "LocalErrorTracker:" (.getMessage error))))


(defn local-error-tracker []
  (->LocalErrorTracker (atom [])))

(defmethod ig/init-key :akvo.lumen.component.error-tracker/local  [_ opts]
  (local-error-tracker))

(defmethod ig/init-key :akvo.lumen.component.error-tracker/local
  [_ _]
  (local-error-tracker))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Local client (no output)
;;;

(defrecord VoidErrorTracker []
  p/IErrorTracker
  (track [this error]))

(defn void-error-tracker []
  (->VoidErrorTracker))

(defmethod ig/pre-init-spec :akvo.lumen.component.error-tracker/void
  [_]
  map?)

(defmethod ig/init-key :akvo.lumen.component.error-tracker/void
  [_ _]
  (void-error-tracker))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; IErrorTracker implementations
;;;

(defn event-map
  ([error]
   (event-map error {}))
  ([error m]
   (let [text (str (ex-data error))]
     (assoc m
            :extra {:ex-data (subs text 0 (min (count text) 4096))}
            :message (.getMessage error)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Ring Sentry tracker wrapper
;;;

(defmethod ig/init-key :akvo.lumen.component.error-tracker/wrap-sentry
  [_ {:keys [dsn opts] :as config}]
  #(raven-clj.ring/wrap-sentry % dsn opts))
