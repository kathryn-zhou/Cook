;;
;; Copyright (c) Two Sigma Open Source, LLC
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;  http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.
;;
(ns cook.mesos.api
  (:require [datomic.api :as d :refer (q)]
            [cook.rest.federation :as fed]
            [metatransaction.core :refer (db)]
            [schema.core :as s]
            [schema.macros :as sm]
            [cook.mesos]
            [compojure.core :refer (routes ANY)]
            [clojure.tools.logging :as log]
            [clojure.string :as str]
            [clojure.core.cache :as cache]
            [clj-http.client :as http]
            [ring.middleware.json]
            [liberator.core :refer [resource defresource]]
            [clj-time.core :as t])
  (:import java.util.UUID))

(def PosDouble
  (s/both double (s/pred pos? 'pos?)))

(def Job
  "A schema for a job"
  {:uuid (s/pred #(instance? UUID %) 'uuid?)
   :command s/Str
   ;; Make sure the job name is a vaild string which can only contain '.', '_', '-' or any word characters and has
   ;; length at most 128.
   :name (s/both s/Str (s/pred #(re-matches #"[\.a-zA-Z0-9_-]{0,128}" %) 'under-128-characters-and-alphanum?))
   :priority (s/both s/Int (s/pred #(<= 0 % 100) 'between-0-and-100))
   :max-retries (s/both s/Int (s/pred pos? 'pos?))
   :max-runtime (s/both s/Int (s/pred pos? 'pos?))
   :cpus (s/both PosDouble (s/pred #(<= % 32) 'under-32-cpus))
   :mem (s/both PosDouble (s/pred #(<= % 200000) 'under-200gb-mem))
   ;; Make sure the user name is vaild. It must begin with a lower case character, end with
   ;; a lower case character or a digit, and has length between 2 to (62 + 2).
   :user (s/both s/Str (s/pred #(re-matches #"\A[a-z][a-z0-9_-]{0,62}[a-z0-9]\z" %) 'lowercase-alphanum?))})

(sm/defn submit-jobs
  [conn jobs :- [Job]]
  (doseq [{:keys [uuid command max-retries max-runtime priority cpus mem user name]} jobs
          :let [txn {:db/id (d/tempid :db.part/user)
                     :job/uuid uuid
                     :job/name name
                     :job/command command
                     :job/custom-executor false
                     :job/user user
                     :job/priority priority
                     :job/max-retries max-retries
                     :job/max-runtime max-runtime
                     :job/state :job.state/waiting
                     :job/resource [{:resource/type :resource.type/cpus
                                     :resource/amount cpus}
                                    {:resource/type :resource.type/mem
                                     :resource/amount mem}]}]]

    @(d/transact conn [txn]))
  "ok")

(defn unused-uuid?
  "Throws if the given uuid is used in datomic"
  [db uuid]
  (when (seq (q '[:find ?j
                  :in $ ?uuid
                  :where
                  [?j :job/uuid ?uuid]]
                db uuid))
    (throw (ex-info (str "UUID " uuid " already used") {:uuid uuid}))))

(defn validate-and-munge-job
  "Takes the user and the parsed json from the job and returns proper
   Job objects, or else throws an exception"
  [db user {:strs [cpus mem uuid command priority max_retries max_runtime name]}]
  (let [munged {:user user
                :uuid (UUID/fromString uuid)
                :name (or name "cookjob") ; Add default job name if user does not provide a name.
                :command command
                :priority (or priority 50) ; Add default priority to maintain backwards compatibility.
                :max-retries max_retries
                :max-runtime (if max_runtime max_runtime Long/MAX_VALUE)
                :cpus (double cpus)
                :mem (double mem)}]
    (s/validate Job munged)
    (unused-uuid? db (:uuid munged))
    munged))

(defn get-executor-states-impl
  "Builds an indexed version of all executor states on the specified slave. Has no cache; takes 100-500ms
   to run."
  [framework-id hostname]
  (let [slave-state (:body (http/get (str "http://" hostname ":5051/state.json")
                                     {:as :json-string-keys
                                      :spnego-auth true}))
        framework-executors (for [framework (concat (get slave-state "frameworks")
                                                    (get slave-state "completed_frameworks"))
                                  :when (= framework-id (get framework "id"))
                                  e (concat (get framework "executors")
                                            (get framework "completed_executors"))]
                              e)]
    (reduce (fn [m {:strs [id] :as executor-state}]
              (assoc m id executor-state))
            {}
            framework-executors)))

(let [cache (-> {}
                (cache/fifo-cache-factory :threshold 10000)
                (cache/ttl-cache-factory :ttl (* 1000 60))
                atom)]
  (defn get-executor-states
    "Builds an indexed version of all executor states on the specified slave. Cached"
    [framework-id hostname]
    (let [run (delay (try (get-executor-states-impl framework-id hostname)
                          (catch Exception e
                            (log/warn e "Failed to get executor state, purging from cache...")
                            (swap! cache cache/evict hostname)
                            nil)))
          cs (swap! cache (fn [c]
                            (if (cache/has? c hostname)
                              (cache/hit c hostname)
                              (cache/miss c hostname run))))
          val (cache/lookup cs hostname)]
      (if val @val @run))))

(defn executor-state->url-path
  "Takes the executor state from the slave json and constructs a URL to query it. Hardcodes fun
   stuff like the port we run the slave on. Users will need to add the file path & offset to their query"
  [host executor-state]
  (str "http://" host ":5051"
       "/files/read.json?path="
       (java.net.URLEncoder/encode (get executor-state "directory") "UTF-8")))

(defn fetch-job-map
  [db fid job-uuid]
  (let [job (d/entity db [:job/uuid job-uuid])
        resources (into {} (map (juxt :resource/type :resource/amount)
                                (:job/resource job)))]
    {:command (:job/command job)
     :uuid (str (:job/uuid job))
     :name (:job/name job)
     :priority (:job/priority job)
     :cpus (:resource.type/cpus resources)
     :mem (:resource.type/mem resources)
     :max_retries  (:job/max-retries job) ; Consistent with input
     :max_runtime (:job/max-runtime job) ; Consistent with input
     :framework_id fid
     :status (name (:job/state job))
     :instances
     (map (fn [instance]
            (let [hostname (:instance/hostname instance)
                  executor-states (get-executor-states fid hostname)
                  url-path (try
                             (executor-state->url-path hostname (get executor-states (:instance/executor-id instance)))
                             (catch Exception e
                               nil))
                  start (:instance/start-time instance)
                  end (:instance/end-time instance)
                  base {:task_id (:instance/task-id instance)
                        :hostname hostname
                        :slave_id (:instance/slave-id instance)
                        :executor_id (:instance/executor-id instance)
                        :status (name (:instance/status instance))}
                  base (if url-path
                         (assoc base :output_url url-path)
                         base)
                  base (if start
                         (assoc base :start_time (.getTime start))
                         base)
                  base (if end
                         (assoc base :end_time (.getTime end))
                         base)]
              base))
          (:job/instance job))}))

;;; On POST; JSON blob that looks like:
;;; {"jobs": [{"command": "echo hello world",
;;;            "uuid": "123898485298459823985",
;;;            "max_retries": 3
;;;            "cpus": 1.5,
;;;            "mem": 1000}]}
;;;
;;; On GET; use repeated job argument
(defn job-resource
  [conn fid]
  (-> (resource
        :available-media-types ["application/json"]
        :allowed-methods [:post :get :delete]
        :malformed? (fn [ctx]
                      (condp contains? (get-in ctx [:request :request-method])
                        #{:get :delete}
                        (if-let [jobs (get-in ctx [:request :params "job"])]
                          (let [jobs (if-not (vector? jobs) [jobs] jobs)]
                            (try
                              [false {::jobs (mapv #(UUID/fromString %) jobs)}]
                              (catch Exception e
                                [true {::error e}])))
                          [true {::error "must supply at least one job query param"}])
                        #{:post}
                        (let [params (get-in ctx [:request :params])
                              user (get-in ctx [:request :authorization/user])]
                          (try
                            (cond
                              (empty? params)
                              [true {::error "must supply at least on job to start. Are you specifying that this is application/json?"}]
                              :else
                              [false {::jobs (mapv #(validate-and-munge-job
                                                      (db conn)
                                                      user
                                                      %)
                                                   (get params "jobs"))}])
                            (catch Exception e
                              (log/warn e "Malformed raw api request")
                              [true {::error e}])))))
        :allowed? (fn [ctx]
                    (condp contains? (get-in ctx [:request :request-method])
                      #{:get :delete}
                      (letfn [(used? [uuid]
                                (try
                                  (unused-uuid? (db conn) uuid)
                                  false
                                  (catch Exception e
                                    true)))]
                        (or (every? used? (::jobs ctx))
                          [false {::error (str "UUID "
                                               (str/join
                                                 \space
                                                 (remove used? (::jobs ctx)))
                                               " didn't correspond to a job")}]))
                      #{:post}
                      true))
        :handle-malformed (fn [ctx]
                            (str (::error ctx)))
        :handle-forbidden (fn [ctx]
                            (str (::error ctx)))
        :processable? (fn [ctx]
                        (if (= :post (get-in ctx [:request :request-method]))
                          (try
                            (log/info "Submitting jobs through raw api:" (::jobs ctx))
                            (submit-jobs conn (::jobs ctx))
                            true
                            (catch Exception e
                              (log/error e "Error submitting jobs through raw api")
                              [false (str e)]))
                          true))
        :post! (fn [ctx]
                 ;; We did the actual logic in processable?, so there's nothing left to do
                 {::results (str/join \space (cons "submitted jobs" (map (comp str :uuid) (::jobs ctx))))})
        :delete! (fn [ctx]
                   (cook.mesos/kill-job conn (::jobs ctx)))
        :handle-ok (fn [ctx]
                     (mapv (partial fetch-job-map (db conn) fid) (::jobs ctx)))
        :handle-created (fn [ctx]
                          (::results ctx)))
      ring.middleware.json/wrap-json-params))

(defn handler
  [conn fid]
  (ANY "/rawscheduler" []
       (job-resource conn fid)))
