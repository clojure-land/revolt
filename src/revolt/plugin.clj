(ns revolt.plugin
  "A home namespace for built-in plugins along with initialization functions."

  (:require [clojure.tools.logging :as log]))

(defprotocol Plugin
  (activate [this ctx] "Activates plugin within given context")
  (deactivate [this ret] "Deactivates plugin"))

(defmulti create-plugin (fn [id config] id))

(defn resolve-from-symbol
  "Loads the namespace denoted by given symbol and calls `init-plugin`
  from within, passing plugin configuration as argument."

  [sym config]
  (require sym)
  (when-let [initializer (ns-resolve sym 'init-plugin)]
    (initializer config)))

(defn initialize-plugin
  "Creates a plugin straight from qualified keyword.

  Loads a corresponding namespace form qualified keyword and invokes
  `create-plugin` multi-method with keyword itself as a dispatch value,
  passing predefined plugin configuration as a second argument."

  [kw config]
  (log/debug "loading plugin" kw)
  (if (qualified-keyword? kw)
    (let [ns (symbol (namespace kw))]
      (require ns)
      (create-plugin kw config))
    (log/errorf "Wrong keyword %s. Qualified keyword required." kw)))

;; default plugins

(defmethod create-plugin ::nrepl [_ config]
  (resolve-from-symbol 'revolt.plugins.nrepl config))

(defmethod create-plugin ::rebel [_ config]
  (resolve-from-symbol 'revolt.plugins.rebel config))

(defmethod create-plugin ::watch [_ config]
  (resolve-from-symbol 'revolt.plugins.watch config))

(defmethod create-plugin ::figwheel [_ config]
  (resolve-from-symbol 'revolt.plugins.figwheel config))
