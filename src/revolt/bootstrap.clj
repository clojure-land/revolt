(ns revolt.bootstrap
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [clojure.tools.logging :as log]
            [clojure.tools.cli :as cli]
            [clojure.java.classpath :as classpath]
            [revolt.plugin :refer [Plugin create-plugin] :as plugin]
            [revolt.utils :as utils]))

(defprotocol PluginContext
  (classpaths [this]   "Returns project classpaths.")
  (target-dir [this]   "Returns a project target directory.")
  (config-val [this k] "Returns a value from configuration map."))


(defonce context (atom {}))
(defonce status  (atom :not-initialized))

(def cli-options
  [["-c" "--config EDN" "EDN resource with revolt configuration."
    :default "revolt.edn"]

   ["-d" "--target DIR" "Target directory where to build artifacts."
    :default "target"]

   ["-p" "--plugins PLUGINS" "Comma-separated list of plugins to activate."
    :default "nrepl,rebel"]

   ["-t" "--tasks TASKS" "Comma-separated list of tasks to run."]])

(defn load-config
  [config-resource]
  (when-let [res (io/resource config-resource)]
    (try
      (let [config (slurp res)]
        (read-string config))
      (catch Exception ex
        (log/error (.getMessage ex))))))

(defn collect-classpaths
  "Returns project classpaths with target directory excluded."
  [target]
  (let [target-path (.. (io/file target) toPath toAbsolutePath)]
    (remove
     #(= target-path (.toPath %))
     (classpath/classpath-directories))))

(defn shutdown
  "Deactivates all the plugins."
  [plugins returns]
  (doseq [p plugins]
    (.deactivate p (get @returns p))))

(defn -main
  [& args]
  (let [params (:options (cli/parse-opts args cli-options))
        target (:target params)
        config (:config params)
        cpaths (collect-classpaths target)]

    (if-let [config-edn (load-config config)]
      (let [returns (atom {})
            plugins (for [plugin (utils/build-params-list params :plugins)
                          :let [kw (keyword plugin)]]
                      (plugin/initialize-plugin kw (kw config-edn)))
            app-ctx  (reify PluginContext
                       (classpaths [this] cpaths)
                       (target-dir [this] target)
                       (config-val [this k] (k config-edn)))]

        (add-watch status :status-watcher
                   (fn [key reference old-state new-state]
                     (log/debug "session" new-state)
                     (when (= new-state :terminated)
                       (.halt (Runtime/getRuntime) 0))))

        ;; register a shutdown hook to be able to deactivate plugins
        (.addShutdownHook (Runtime/getRuntime)
                          (Thread. #(do
                                      (shutdown plugins returns)
                                      (reset! status :terminated))))

        ;; set global application context
        (reset! context app-ctx)

        ;; activate all the plugins sequentially one after another
        (doseq [p plugins]
          (when-let [ret (.activate p @context)]
            (swap! returns conj {p ret})))

        ;; set global application context
        (reset! status :initialized))

      (log/error "Configuration not found."))))
