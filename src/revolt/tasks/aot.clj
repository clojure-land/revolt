(ns revolt.tasks.aot
  (:require [clojure.tools.namespace.find :as tnfind]
            [revolt.utils :as utils]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]))

(defn compile-namespaces
  [namespaces]
  (doseq [namespace namespaces]
    (let [nstr (str namespace)]
      (when-not (.startsWith nstr "revolt.")
        (log/info "compiling" nstr)
        (compile namespace)))))

(defn invoke
  [ctx {:keys [extra-namespaces]} classpaths target]
  (let [classes (utils/ensure-relative-path target "classes")]
    (.mkdirs (io/file classes))

    (utils/timed
     "AOT"
     (binding [*compile-path* classes]
       (doseq [cp classpaths
               :when (.isDirectory cp)
               :let  [namespaces (tnfind/find-namespaces-in-dir cp)]]
         (compile-namespaces namespaces))
       (compile-namespaces extra-namespaces)))
    (assoc ctx :aot? true)))
