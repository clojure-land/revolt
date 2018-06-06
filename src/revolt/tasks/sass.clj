(ns revolt.tasks.sass
  (:require [sass4clj.core :as sass]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [revolt.utils :as utils])
  (:import  (java.io File)))

(defn invoke
  [{:keys [resources options]} classpaths target]

  (let [assets (utils/ensure-relative-path target "assets")]

    ;; run SASS compilation for every single resource
    ;; passed in resources collection.

    (run!
     (fn [[resource relative-path]]
       (utils/timed
        (str "SASS " relative-path)
        (sass/sass-compile-to-file
         resource
         (io/file assets (str/replace relative-path #"\.scss$" ".css"))
         options)))

     (eduction
      (map (juxt io/resource identity))
      resources))))
