(ns akvo.lumen.lib.invite-impl
  (:require [akvo.lumen.component.keycloak :as keycloak]
            [akvo.lumen.component.emailer :as emailer]
            [cheshire.core :as json]
            [clj-time.core :as t]
            [clj-time.coerce :as c]
            [hugsql.core :as hugsql]
            [ring.util.response :refer [not-found response]]))

(hugsql/def-db-fns "akvo/lumen/lib/invite.sql")

(defn active-invites
  "Returns all active invites"
  [tenant-label tenant-conn keycloak claimed-roles]
  (response (select-active-invites tenant-conn)))

(defn do-create-invite [tenant-conn emailer keycloak email claims]
  (if (keycloak/user? keycloak email)
    (let [expiration-time (c/to-sql-time (t/plus (t/now) (t/weeks 2)))
          db-rec (first (insert-invite tenant-conn
                                       {:email email
                                        :expiration_time expiration-time
                                        :author claims}))
          host "http://t1.lumen.localhost:3000"
          mail-body (format "%s/verify/%s" host (:id db-rec))]
      (emailer/send-email emailer mail-body))
    (prn (format "Tried to invite non existing user with email (%s)" email))))

(defn create
  "Creates a new invite"
  [tenant-conn emailer keycloak roles {:strs [email]} claims]
  ;; If existing user and no other active invite on same email
  (do-create-invite tenant-conn emailer keycloak email claims)
  (response {:invite-job-status "started"}))

(defn accept-invite
  ""
  [tenant-conn tenant emailer keycloak id]
  (if-let [{email :email} (first (consume-invite tenant-conn {:id id}))]
    (let [accept-status (keycloak/add-user-with-email keycloak tenant email)]
      (response {:accepted accept-status}))
    (do
      (prn (format "Tried to verify invite with id: %s" id))
      (response {:status 422
                 :body "Could not verify invite."}))))
