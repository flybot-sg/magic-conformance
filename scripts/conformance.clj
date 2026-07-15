(ns conformance
  "Runs the MAGIC conformance checks from libs.edn: clone each library, write its
  magic.edn when the repo ships none, then run `nos build` and `nos test`."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.pprint :refer [pprint]]
            [clojure.string :as str]))

(defn- slug [lib-key]
  (str/replace (str lib-key) #"[^A-Za-z0-9]+" "-"))

^:rct/test
(comment
  (slug "robertluo/fun-map")     ;=> "robertluo-fun-map"
  (slug 'clojure/clr.test.check) ;=> "clojure-clr-test-check"
  )

(defn- resolve-lib
  "The [key entry] whose key matches sel by full coord, slug, or name, else nil."
  [manifest sel]
  (some (fn [[k v]] (when (#{(str k) (slug k) (name k)} sel) [k v])) manifest))

^:rct/test
(comment
  (resolve-lib '{a/b {:git/ref "m"}} "b")   ;=> [a/b {:git/ref "m"}]
  (resolve-lib '{a/b {:git/ref "m"}} "a/b") ;=> [a/b {:git/ref "m"}]
  (resolve-lib '{a.c/b {:git/ref "m"}} "a-c-b") ;=> [a.c/b {:git/ref "m"}]
  (resolve-lib '{a/b {}} "nope")            ;=> nil
  )

(defn- clone-commands
  "The git argv sequence that shallow-fetches ref of url into a fresh clone."
  [url ref]
  [["git" "init" "-q"]
   ["git" "remote" "add" "origin" url]
   ["git" "fetch" "-q" "--depth" "1" "origin" ref]
   ["git" "-c" "advice.detachedHead=false" "checkout" "-q" "FETCH_HEAD"]])

^:rct/test
(comment
  (get-in (clone-commands "u" "r") [1 4]) ;=> "u"
  (get-in (clone-commands "u" "r") [2 6]) ;=> "r"
  )

(defn- read-manifest! []
  (edn/read-string (slurp "libs.edn")))

(defn- clone! [url ref dir]
  (println (format "  clone %s @ %s" url ref))
  (when (fs/exists? dir) (fs/delete-tree dir))
  (fs/create-dirs dir)
  (doseq [cmd (clone-commands url ref)]
    (apply p/shell {:dir (str dir)} cmd)))

(defn- write-config! [dir file-name value]
  (let [target (fs/file dir file-name)]
    (cond
      (fs/exists? target) (println (str "  " file-name ": repo ships its own, manifest value ignored"))
      value               (do (println (str "  " file-name ": written from manifest"))
                              (spit target (with-out-str (pprint value))))
      :else               (println (str "  " file-name ": none, using nos defaults")))))

(defn- run-task! [dir task]
  (println (str "\n  $ nos " (name task)))
  (let [{:keys [exit]} (p/shell {:dir (str dir) :continue true} "nos" (name task))]
    {:task task :ok (zero? exit) :exit exit}))

(defn- run-entry! [[k {:keys [magic deps-clr tasks] :git/keys [url ref]}]]
  (let [dir (fs/file "work" (slug k))]
    (println (str "\n== " k " =="))
    (clone! url ref dir)
    (write-config! dir "deps-clr.edn" deps-clr)
    (write-config! dir "magic.edn" magic)
    (let [results (mapv #(run-task! dir %) (or tasks '[build test]))]
      (println)
      (doseq [{:keys [task ok exit]} results]
        (println (format "  %-6s %s (exit %d)" (name task) (if ok "PASS" "FAIL") exit)))
      {:lib k :ok (every? :ok results)})))

(defn check!
  "Check one lib and exit non-zero on failure."
  [sel]
  (let [manifest (read-manifest!)]
    (if-let [entry (and sel (resolve-lib manifest sel))]
      (let [{:keys [ok lib]} (run-entry! entry)]
        (println (str (if ok "\nOK: " "\nFAILED: ") lib))
        (System/exit (if ok 0 1)))
      (do (println (str "usage: bb check <lib>; known: "
                        (str/join ", " (map str (keys manifest)))))
          (System/exit 2)))))

(defn check-all!
  "Check every lib, print a summary, and exit non-zero if any failed."
  []
  (let [results (mapv (fn [entry]
                        (try (run-entry! entry)
                             (catch Exception e
                               (println (str "  ERROR: " (ex-message e)))
                               {:lib (first entry) :ok false})))
                      (read-manifest!))]
    (println "\n=== summary ===")
    (doseq [{:keys [lib ok]} results]
      (println (format "  %-30s %s" (str lib) (if ok "PASS" "FAIL"))))
    (System/exit (if (every? :ok results) 0 1))))
