(ns revolt.task
  "A home namespace for built-in tasks along with initialization functions."

  (:require [io.aviso.ansi]
            [clojure.string :as str]
            [revolt.context :as context]
            [revolt.tasks.aot :as aot]
            [revolt.tasks.cljs :as cljs]
            [revolt.tasks.sass :as sass]
            [revolt.tasks.test :as test]
            [revolt.tasks.info :as info]
            [revolt.tasks.codox :as codox]
            [revolt.tasks.clean :as clean]
            [revolt.tasks.assets :as assets]
            [revolt.tasks.capsule :as capsule]
            [revolt.utils :as utils]
            [clojure.tools.logging :as log]))

(defprotocol Task
  (invoke   [this input ctx] "Runs task with provided input data and pipelined context.")
  (notify   [this path ctx]  "Handles notification with java.nio.file.Path typed argument.")
  (describe [this]           "Returns human readable task description."))

(defmulti create-task (fn [id opts classpaths target] id))

(defn create-task-with-args
  "A helper function to load a namespace denoted by namespace-part of
  provided qualified keyword, and create a task."

  [kw opts classpaths target]
  (if (qualified-keyword? kw)
    (let [ns (namespace kw)]
      (log/debug "initializing task" kw)
      (try
        (require (symbol ns))
        (create-task kw opts classpaths target)
        (catch Exception ex
          (log/errorf "Cannot initialize task %s: %s" kw (.getMessage ex)))))
    (log/errorf "Wrong keyword %s. Qualified keyword required." kw)))


(defn require-task*
  "Creates a task instance from qualified keyword.

  Loads a corresponding namespace from qualified keyword and invokes
  `create-task` multi-method with keyword as a dispatch value,
  passing task options, project classpaths and project target
  building directory as arguments."

  [kw]
  (context/with-context ctx
    (let [task (create-task-with-args kw
                                      (.config-val ctx kw)
                                      (.classpaths ctx)
                                      (.target-dir ctx))]
      (fn [& [input context]]

        ;; as we operate on 2 optional parameter, 3 cases may happen:
        ;;
        ;; 1. we will get an input only, like (info {:version 0.0.2})
        ;;    this is a case where no context was given, and it should
        ;;    be created automatically.
        ;;
        ;; 2. we will get both: input and context.
        ;;    this is a case where context was given either directly
        ;;    along with input, eg. `(info {:version} app-ctx)` or task has
        ;;    partially defined input, like:
        ;;
        ;;     (partial capsule {:version 0.0.2})
        ;;
        ;;    and has been composed with other tasks:
        ;;
        ;;     (def composed-task (comp capsule info))
        ;;
        ;;    invocation of composed task will pass a context from one task
        ;;    to the other. tasks having input partially defined will get
        ;;    an input as a first parameter and context as a second one.
        ;;
        ;; 3. we will get a context only.
        ;;    this a slight variation of case 2. and may happen when task is
        ;;    composed together with others and has no partially defined input
        ;;    parameter. in this case task will be called with one parameter
        ;;    only - with an updated context.
        ;;
        ;;  to differentiate between case 1 and 3 a type check on first argument
        ;;  is applied. a ::ContextMap type indicates that argument is a context
        ;;  (case 3), otherwise it is an input argument (case 1).

        (let [context-as-input? (= (type input) ::ContextMap)
              context-map (or context
                              (when context-as-input? input)
                              ^{:type ::ContextMap} {})
              input-argument  (when-not context-as-input? input)]

          (cond

            ;; handle special arguments (keywords)
            (keyword? input-argument)
            (condp = input-argument
              :describe
              (println (io.aviso.ansi/yellow (.describe task)))
              (throw (Exception. "Keyword parameter not recognized by task.")))

            ;; handle notifications
            (instance? java.nio.file.Path input-argument)
            (.notify task input-argument context-map)

            :else
            (.invoke task input-argument context-map)))))))

(def require-task-cached (memoize require-task*))

(defmacro require-task
  [kw & [opt arg]]
  `(when-let [task# (require-task-cached ~kw)]
     (intern *ns*
             (or (and (= ~opt :as) '~arg) '~(symbol (name kw)))
             task#)))

(defmacro require-all [kws]
  `(when (coll? ~kws)
     [~@(map #(list `require-task %) kws)]))

(defn run-tasks-from-string
  "Decomposes given input string into collection of [task options] tuples
  and sequentially runs them one after another.

  `tasks-str` is a comma-separated list of tasks to run, along with their
  optional parameters given as \"parameter=value\" expressions, like:

       clean,info:env=test:version=1.2,aot,capsule

  Returns resulting context map."

  [tasks-str]
  (when-let [required-tasks (seq (utils/make-params-coll tasks-str "revolt.task"))]
    (loop [tasks required-tasks
           context {}]
      (if-let [[task opts] (first tasks)]
        (recur (rest tasks)
               (or (when-let [task-fn (require-task-cached (keyword task))]
                     (task-fn opts context))
                   context))
        context))))

;; built-in tasks

(defmethod create-task ::clean [_ opts classpaths target]
  (reify Task
    (invoke [this input ctx]
      (clean/invoke ctx input target))
    (describe [this]
      "Target directory cleaner.

Cleans target directory.")))

(defmethod create-task ::sass [_ opts classpaths target]
  (reify Task
    (invoke [this input ctx]
      (let [in (if (map? input)
                 (merge opts input)
                 (assoc opts :file input))]
        (sass/invoke ctx in classpaths target)))
    (notify [this path ctx]
      (.invoke this path ctx))
    (describe [this]
      "CSS preprocessor.

Takes Sass/Scss files and turns them into CSS ones.

Options:
--------

  :source-path - relative directory with sass/scss files to transform
  :output-dir - directory where to store generated CSS files
  :sass-options - sass compiler options
")))

(defmethod create-task ::assets [_ opts classpaths target]
  (let [default-opts {:update-with-exts ["js" "css" "html"]}
        options (merge default-opts opts)]
    (reify Task
      (invoke [this input ctx]
        (assets/invoke ctx (merge options input) classpaths target))
      (notify [this path ctx]
        (log/warn "Notification is not handled by \"assets\" task.")
        ctx)
      (describe [this]
        "Static assets fingerprinter.

Fingerprints static assets like images, scripts or styles.

Options:
--------

  :assets-paths - collection of paths with assets to fingerprint
  :exclude-paths - collection of paths to exclude from fingerprinting
  :update-with-exts - extensions of files to update with new references to fingerprinted assets

By default all javascripts, stylesheets and HTML resources are scanned for references to
fingerprinted assets. Any recognized reference is being replaced with fingerprinted version.
"))))

(defmethod create-task ::aot [_ opts classpaths target]
  (reify Task
    (invoke [this input ctx]
      (aot/invoke ctx (merge opts input) classpaths target))
    (describe [this]
      "Ahead-Of-Time compilation.

Options:
--------

  :exta-namespaces - collection of additional namespaces to compile.
")))

(defmethod create-task ::cljs [_ opts classpaths target]
  (require 'cljs.build.api)

  (let [cljs-api  (find-ns 'cljs.build.api)
        build-fn  (ns-resolve cljs-api 'build)
        inputs-fn (ns-resolve cljs-api 'inputs)]
    (when (and build-fn inputs-fn)
      (reify Task
        (invoke [this input ctx]
          (let [options (merge-with merge opts input)]
            (cljs/invoke ctx options classpaths target inputs-fn build-fn)))
        (notify [this path ctx]
          (.invoke this nil ctx))
        (describe [this]
          "CLJS compilation.

Turns clojurescripts into javascripts with help of ClojureScript compiler.

Options:
--------

  :compiler - global clojurescript compiler options used for all builds
  :builds - collection of builds, where each build consists of:

            :id - build identifier
            :source-paths - project-relative path of clojurescript files to compile
            :compiler - clojurescript compiler options (https://clojurescript.org/reference/compiler-options)
")))))

(defmethod create-task ::test [_ opts classpaths target]
  (System/setProperty "java.awt.headless" "true")
  (let [options (merge test/default-options opts)]
    (reify Task
      (invoke [this input ctx]
        (test/invoke ctx (merge options input)))
      (notify [this path ctx]
        (.invoke this nil ctx))
      (describe [this]
        "Clojure tests runner based on bat-test (https://github.com/metosin/bat-test).

Options:
--------

  :test-matcher - regex used to select test namespaces (defaults to #\".*test\")
  :parallel - run tests in parallel? (defaults to false)
  :report - reporting function (:pretty, :progress or :junit)
  :filter - function to filter the test vars
  :notify - sound notification? (defaults to true)
  :on-start - function to be called before running tests (after reloading namespaces)
  :on-end - function to be called after running tests
  :cloverage - enable Cloverage coverage report? (defaults to false)
  :cloverage-opts - Cloverage options (defaults to nil)
"))))

(defmethod create-task ::codox [_ opts classpaths target]
  (reify Task
    (invoke [this input ctx]
      (codox/invoke ctx (merge opts input) target))
    (describe [this]
      "API documentation generator.

Options:
--------

  :name - project name, eg. \"edge\"
  :package - symbol describing project package, eg. defunkt.edge
  :version - project version, eg. \"1.2.0\"
  :description - project description to be shown
  :namespaces - collection of namespaces to document (by default all namespaces are taken)
")))

(defmethod create-task ::info [_ opts classpaths target]
  (reify Task
    (invoke [this input ctx]
      (info/invoke ctx (merge opts input) target))
    (describe [this]
      "Project info generator.

Generates map of project-specific information used by other tasks.

Options:
--------

  :name - project name, eg. \"edge\"
  :package - symbol describing project package, eg defunkt.edge
  :version - project version
  :description - project description to be shown
")))

(defmethod create-task ::capsule [_ opts classpaths target]
  (reify Task
    (invoke [this input ctx]
      (capsule/invoke ctx (merge opts input) target))
    (describe [this]
      "Capsule packager.

Generates an uberjar-like capsule (http://www.capsule.io).

Options:
--------

  :capsule-type - type of capsule, one of :empty, :thin or :fat (defaults to :fat)
  :exclude-paths - collection of project paths to exclude from capsule
  :output-jar - project related path of output jar, eg. dist/foo.jar
  :main - main class to be run

Capsule options (http://www.capsule.io/reference):

  :min-java-version
  :min-update-version
  :java-version
  :jdk-required?
  :jvm-args
  :environment-variables
  :system-properties
  :security-manager
  :security-policy
  :security-policy-appended
  :java-agents
  :native-agents
  :native-dependencies
  :capsule-log-level
")))
