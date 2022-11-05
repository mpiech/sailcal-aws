(ns sailcal.handler
  (:require
   [nrepl.server :as nrepl]
   [compojure.core :as cpj]
   [compojure.route :as cpjroute]
   [ring.middleware.defaults :as ring]
   [net.cgrand.enlive-html :as enlive]
   [clj-time.core :as time]
   [clj-time.format :as ftime]
   [clj-time.coerce :as ctime]
   [clojure.java.jdbc :as jdbc]
   [clojure.data.json :as json]
   [clojure.java.io :as io]
   [monger.core :as mg]
   [monger.credentials :as mcr]
   [monger.collection :as mc]
   [monger.operators :refer :all]
   ; to quiet Mongo verbose logging
;   [taoensso.timbre :as timbre]
   )
;  (:import
;   [java.util.logging Level Logger]
;   )
; abandoned attempt to reduce Mongo logging 10.16.22
  )

;;;
;;; Static parameters
;;;

(def loc-timezone "America/Los_Angeles")
(def gmaps-key (System/getenv "GMAPS_KEY"))
                                        
; to quiet Mongo verbose logging
;(timbre/merge-config! {:min-level `[[#{"org.mongodb.*"} :info]]})

;(def mongo-logger (Logger.getLogger "com.mongodb"))
;(Logger.setLevel mongo-logger Logger.Level.SEVERE)

;;;
;;; SQL database of reservations
;;;

(def rsvdb (System/getenv "RSVDB"))
(def rsvdbtype (if (or (= rsvdb "crunchy")
                       (= rsvdb "cockroach"))
                 "postgresql"
                 "mysql"))

(def dbspec
  (case rsvdb
    "openshift" {:connection-uri
                 (str 
                  "jdbc:mysql://"
                  (System/getenv "MYSQL_SERVICE_HOST") ":"
                  (System/getenv "MYSQL_SERVICE_PORT") "/"
                  (System/getenv "SLCAL_SQLDB")
                  "?user=" (System/getenv "SLCAL_SQLUSR")
                  "&password=" (System/getenv "SLCAL_SQLPWD")
                  "&useSSL=false")
                 }
    "crunchy" {:dbtype "postgresql"
               :dbname (System/getenv "SLCAL_SQLDB")
               :host (System/getenv "PGHOST")
               :user (System/getenv "PGUSER")
               :password (System/getenv "PGPASSWORD")
               :ssl true
               :sslmode "require"
               }
    "cockroach" {:connection-uri
                 (str 
                  "jdbc:postgresql://"
                  (System/getenv "COCKROACH_HOST") ":"
                  (System/getenv "COCKROACH_PORT") "/"
                  (System/getenv "SLCAL_SQLDB")
                  "?user=" (System/getenv "COCKROACH_USR")
                  "&password=" (System/getenv "COCKROACH_PWD")
                  "&options=" (System/getenv "COCKROACH_OPTIONS")
                  )
                 }
    "local" {:dbtype "mysql"
             :dbname (System/getenv "SLCAL_SQLDB")
             :subname (str
                       "//localhost:3306/"
                       (System/getenv "SLCAL_SQLDB"))
             :user (System/getenv "SLCAL_SQLUSR")
             :password (System/getenv "SLCAL_SQLPWD")
             }
    ))


;;;
;;; MongoDB database of sailing tracks
;;;

;;; Mongo connection and db objects

(def mgconn
  (case (System/getenv "TRKDB")
    "openshift" (let [host (System/getenv "MONGODB_SERVICE_HOST")
                      port (Integer/parseInt
                            (System/getenv "MONGODB_SERVICE_PORT"))
                      uname (System/getenv "SLCAL_MGUSR")
                      dbname (System/getenv "SLCAL_MGDB")
                      pwd-raw (System/getenv "SLCAL_MGPWD")
                      pwd (.toCharArray pwd-raw)
                      creds (mcr/create uname dbname pwd)]
                  (mg/connect-with-credentials host port creds))
    "local" (mg/connect)
    "atlas" nil
    ))

(def mgdb
  (case (System/getenv "TRKDB")
    "atlas" (let [atlas-username (System/getenv "ATLAS_USERNAME")
                  atlas-password (System/getenv "ATLAS_PASSWORD")
                  atlas-host (System/getenv "ATLAS_HOST")
                  atlas-db (System/getenv "ATLAS_DB")]
              (:db (mg/connect-via-uri
                    (str "mongodb+srv://"
                         atlas-username ":"
                         atlas-password "@"
                         atlas-host "/"
                         atlas-db))))
    (mg/get-db mgconn (System/getenv "ATLAS_DB"))
    ))


;;;
;;; Date/Time utilities
;;;

(defn sqldtobj-to-dtobj [sqldtobj]
  (time/to-time-zone
   (ctime/from-sql-time sqldtobj)
   (time/time-zone-for-id loc-timezone)))


;;;
;;; Database read functions
;;;

; reservations and other 'events' from SQL database

(defn db-read-dtobjs [table start-dtstr end-dtstr]
  (let [qstr (if (= rsvdbtype "mysql")
               (str
                "SELECT DISTINCT res_date "
                "FROM " table
                " WHERE res_date >= \""
                start-dtstr
                "\" AND res_date <= \""
                end-dtstr "\"")
               (str
                "SELECT DISTINCT res_date "
                "FROM " table
                " WHERE CAST (res_date AS TIMESTAMP) >= "
                "CAST ('" start-dtstr "' AS TIMESTAMP) "
                "AND CAST (res_date AS TIMESTAMP) <= "
                "CAST ('" end-dtstr "' AS TIMESTAMP)"))]
    (map (fn [x]
           (sqldtobj-to-dtobj (:res_date x)))
         (jdbc/query dbspec [qstr]))
    ))

; sailing tracks from MongoDB

(defn trdb-read-dtobjs [coll start-dtstr end-dtstr]
  (map (fn [x] (:date x))
       (mc/find-maps mgdb coll {:date
                                {$gte start-dtstr
                                 $lte end-dtstr}})
       )
  )

; for debugging in nREPL
; (trdb-read-dtobjs "tracks" "2021-10-01" "2021-12-31")

;;;
;;; Enlive - Clojure HTML templating
;;;

;;; for index, simply show index.html with vars replaced

(enlive/deftemplate index "sailcal/index.html.enlive"
  []
  [:#gmap] (enlive/replace-vars {:gmapskey gmaps-key})
  )

;;;
;;; Handlers for Compojure - Clojure web app routing
;;;

;;; main calendar page - enlive displays index.html SPA

(defn handler-get-index []
  (index)
  )

(defn handler-get-track [params]
  (let [trdate (get params "date")
        rawTrack (mc/find-one-as-map mgdb "tracks"
                                     {:date trdate})
        ]
    (if rawTrack
      (json/write-str (:points rawTrack))
      (json/write-str '[]))
    )
  )

;;; REST API for AJAX call to get dates as JSON
;;; returns array of e.g. {:title "Boat Rsvd" :start "2021-12-10"}

(defn handler-get-events [params]
  (let [start (get params "start" "2021-12-01")
        end (get params "end" "2021-12-31")
        dummy (Class/forName "org.postgresql.Driver")]
    (json/write-str
     (concat
      (map (fn [x]
             {:title "Boat Reserved",
              :start (ftime/unparse
                      (ftime/formatters
                       :date)
                      x)
              })
           (db-read-dtobjs "reservations" start end))
; example of other types of events currently not being captured
;      (map (fn [x]
;             {:title "Bareboat",
;              :start (ftime/unparse
;                      (ftime/formatters
;                       :date)
;                      x)
;              })
;           (db-read-dtobjs "bareboat" start end))
      (map (fn [x]
             {:title "Track",
              :start x
              })
           (trdb-read-dtobjs "tracks" start end))))
    ))

;;;
;;; Compojure Routing
;;;

(cpj/defroutes app-routes
  (cpj/HEAD "/" [] "")
  (cpj/GET "/" []
    (handler-get-index))
  (cpj/GET "/track" {params :query-params}
    (handler-get-track params))
  (cpj/GET "/events" {params :query-params}
    (handler-get-events params))
  (cpjroute/files "/")
  (cpjroute/resources "/")
  (cpjroute/not-found "Not found.")
  )

;; start nREPL server

(defonce server (nrepl/start-server :port 7888))

;; generated by 'lein ring new'

(def app
  (ring/wrap-defaults app-routes
                      (assoc ring/site-defaults :proxy true)))

;;; EOF
