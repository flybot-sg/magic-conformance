(ns conformance
  "MAGIC conformance runner for libs.edn: clone each library, inject its
  magic.edn / deps-clr.edn when the repo ships none, then run its nos tasks.
  When the clone's deps-clr.edn declares an executable :test alias, a
  `cljr -X:test` task is appended, so the lib also runs on ClojureCLR
  (opt out per entry with :cljr false).
  A clone carrying no CLR config of its own gets scanned one level deep, and
  each directory that carries some runs as a component.
  Incremental: a result is a pure function of (ref SHA, MAGIC version, spec),
  cached in results.edn and reused when all three are unchanged. See README."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.pprint :refer [pprint]]
            [clojure.string :as str]))

(def ^:private config
  "Optional per-manifest knobs from conformance.edn in the working dir, so the
  same runner can drive different manifests without code changes. Recognized
  keys: :default-ref, :magic-version."
  (if (fs/exists? "conformance.edn") (edn/read-string (slurp "conformance.edn")) {}))

(def ^:private default-ref (:default-ref config "master"))
(def ^:private build-markers #{"dotnet.clj" "magic.edn"})
(def ^:private clr-markers (conj build-markers "deps-clr.edn"))
(def ^:private results-file "results.edn")

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
  (resolve-lib '{a/b {:git/ref "m"}} "b")       ;=> [a/b {:git/ref "m"}]
  (resolve-lib '{a/b {:git/ref "m"}} "a/b")     ;=> [a/b {:git/ref "m"}]
  (resolve-lib '{a.c/b {:git/ref "m"}} "a-c-b") ;=> [a.c/b {:git/ref "m"}]
  (resolve-lib '{a/b {}} "nope")                ;=> nil
  )

(defn- choose-tasks
  "nos tasks for a clone: explicit :tasks, else magic.edn → [build test],
  dotnet.clj → [dotnet/build dotnet/run-tests], neither → nos defaults."
  [present explicit]
  (or explicit
      (cond
        (present "magic.edn")  '[build test]
        (present "dotnet.clj") '[dotnet/build dotnet/run-tests]
        :else                  '[build test])))

^:rct/test
(comment
  (choose-tasks #{"magic.edn"} nil)  ;=> [build test]
  (choose-tasks #{"dotnet.clj"} nil) ;=> [dotnet/build dotnet/run-tests]
  (choose-tasks #{"deps.edn"} nil)   ;=> [build test]
  (choose-tasks #{} '[build])        ;=> [build]
  )

(defn- cljr-test-task
  "The ClojureCLR lane invocation, when deps-clr declares a :test alias that
  `cljr -X:test` can execute (an :exec-fn) and the entry does not set
  :cljr false. Else nil."
  [deps-clr-map cljr-opt]
  (when (and (not (false? cljr-opt))
             (get-in deps-clr-map [:aliases :test :exec-fn]))
    ["cljr" "-X:test"]))

^:rct/test
(comment
  (cljr-test-task {:aliases {:test {:exec-fn 'a/b}}} nil)      ;=> ["cljr" "-X:test"]
  (cljr-test-task {:aliases {:test {:exec-fn 'a/b}}} false)    ;=> nil
  (cljr-test-task {:aliases {:test {:extra-paths ["t"]}}} nil) ;=> nil
  (cljr-test-task nil nil)                                     ;=> nil
  )

(defn- task->argv
  "A vector task runs as its own argv; a symbol runs as `nos <symbol>`."
  [task]
  (if (vector? task) (mapv str task) ["nos" (str task)]))

(defn- task->label [task]
  (if (vector? task) (str/join " " task) (str task)))

^:rct/test
(comment
  (task->argv 'build)             ;=> ["nos" "build"]
  (task->argv ["bb" "clr-test"])  ;=> ["bb" "clr-test"]
  (task->label 'test)            ;=> "test"
  (task->label ["bb" "clr-test"]) ;=> "bb clr-test"
  )

(defn- spec-hash
  "Digest of the manifest bits affecting the result independently of the SHA:
  the task spec, any injected magic.edn / deps-clr.edn config, the :cljr
  opt-out, and the same four per component. Keys the runner ignores, :note
  and friends, stay out of it so editing a note reuses the cached result.
  An entry with no :components hashes the four alone: any other digest there
  misses every result recorded in results.edn."
  [{:keys [tasks magic deps-clr cljr components]}]
  (str (hash (cond-> [tasks magic deps-clr cljr] components (conj components)))))

^:rct/test
(comment
  (= (spec-hash {}) (spec-hash {}))                                     ;=> true
  (not= (spec-hash '{:tasks [build]}) (spec-hash '{:tasks [test]}))     ;=> true
  (not= (spec-hash {:magic {:build {}}}) (spec-hash {}))                ;=> true
  (not= (spec-hash {:deps-clr {:paths ["src"]}}) (spec-hash {}))        ;=> true
  (not= (spec-hash {:cljr false}) (spec-hash {}))                       ;=> true
  (not= (spec-hash {:components {"a" {}}}) (spec-hash {}))              ;=> true
  (= (spec-hash {:note "x"}) (spec-hash {:note "y"}))                   ;=> true
  ;; the digest results.edn holds for every entry with no :components
  (= (spec-hash {}) (str (hash [nil nil nil nil])))                     ;=> true
  )

(defn- cacheable?
  "Whether prev is reusable pending a SHA match: not forced, MAGIC version
  unchanged, prior exists, spec matches."
  [force? prior-magic cur-magic prev spec]
  (boolean (and (not force?)
                (= prior-magic cur-magic)
                prev
                (= (:spec prev) spec))))

^:rct/test
(comment
  (cacheable? false "v1" "v1" {:spec "s"} "s")     ;=> true
  (cacheable? true  "v1" "v1" {:spec "s"} "s")     ;=> false
  (cacheable? false "v1" "v2" {:spec "s"} "s")     ;=> false
  (cacheable? false "v1" "v1" nil "s")             ;=> false
  (cacheable? false "v1" "v1" {:spec "s"} "other") ;=> false
  )

(def ^:private magic-version
  "MAGIC version shipped by the pinned ci-clj-clr image; from conformance.edn
  (:magic-version) or this default. Bump it in the same commit that bumps the
  image tag: it invalidates the cache so every lib re-runs under the new compiler."
  (:magic-version config "v0.12.0"))

(defn- read-manifest! []
  (edn/read-string (slurp "libs.edn")))

(defn- git! [dir & args]
  (apply p/shell {:dir (str dir) :continue true :out :string :err :string} "git" args))

(defn- remote-sha
  "The SHA ref points to on the remote, via ls-remote (no clone), else nil.
  A raw commit SHA as ref returns nil (ls-remote lists refs, not commits), so a
  SHA-pinned entry skips the cache and re-clones every run."
  [url ref]
  (let [{:keys [out exit]} (p/shell {:out :string :err :string :continue true}
                                    "git" "ls-remote" url ref)]
    (when (and (zero? exit) (seq (str/trim (or out ""))))
      (-> out str/split-lines first (str/split #"\s+") first))))

(defn- head-sha [dir]
  (some-> (:out (git! dir "rev-parse" "HEAD")) str/trim not-empty))

(defn- short-sha [sha]
  (when sha (subs sha 0 (min 8 (count sha)))))

^:rct/test
(comment
  (short-sha nil)          ;=> nil
  (short-sha "abc")        ;=> "abc"
  (short-sha "0123456789") ;=> "01234567"
  )

(defn- clone!
  "Fresh shallow-clone url at ref into dir; return the ref used."
  [url ref dir]
  (when (fs/exists? dir) (fs/delete-tree dir))
  (fs/create-dirs dir)
  (git! dir "init" "-q")
  (git! dir "remote" "add" "origin" url)
  (let [{:keys [exit err]} (git! dir "fetch" "-q" "--depth" "1" "origin" ref)]
    (when-not (zero? exit)
      (throw (ex-info (str "fetch failed for " url " @ " ref
                           (when (seq err) (str ": " (str/trim err))))
                      {:url url :ref ref}))))
  (git! dir "-c" "advice.detachedHead=false" "checkout" "-q" "FETCH_HEAD")
  (println (format "  clone %s @ %s" url ref))
  ref)

(defn- run-task! [dir label task]
  (let [argv (task->argv task)]
    (println (str "\n  $ " (str/join " " argv)))
    (let [{:keys [exit]} (apply p/shell {:dir (str dir) :continue true} argv)]
      {:task (str label (task->label task)) :ok (zero? exit) :exit exit})))

(defn- present-configs [dir]
  (into #{} (filter #(fs/exists? (fs/file dir %))) build-markers))

(defn- clr-dir?
  "Whether dir carries CLR config of its own."
  [dir]
  (boolean (some #(fs/exists? (fs/file dir %)) clr-markers)))

(defn- discover-components
  "Directory names one level under dir that carry CLR config, sorted, or nil.
  A dir carrying config of its own is a plain repo, and returns nil."
  [dir]
  (when-not (clr-dir? dir)
    (->> (fs/list-dir dir)
         (filter fs/directory?)
         (filter clr-dir?)
         (map #(str (fs/file-name %)))
         sort
         seq)))

(defn- write-config!
  "Write value into dir as file-name, but only when the clone ships none of its
  own: a lib's own config always wins, and a nil value falls through to nos
  defaults. Runs before task detection so an injected magic.edn is picked up."
  [dir file-name value]
  (let [target (fs/file dir file-name)]
    (cond
      (fs/exists? target) (println (str "  " file-name ": repo ships its own, manifest value ignored"))
      value               (do (println (str "  " file-name ": written from manifest"))
                              (spit target (with-out-str (pprint value))))
      :else               (println (str "  " file-name ": none, using nos defaults")))))

(defn- read-deps-clr
  "The clone's deps-clr.edn as data (its own or the injected one), nil when
  absent or unreadable."
  [dir]
  (let [f (fs/file dir "deps-clr.edn")]
    (when (fs/exists? f)
      (try (edn/read-string (slurp f)) (catch Exception _ nil)))))

(defn- run-dir!
  "Inject config into dir, choose its tasks, run them, and return their results.
  label prefixes each task name, so a monorepo's components stay apart."
  [dir label {:keys [tasks magic deps-clr cljr]}]
  (write-config! dir "deps-clr.edn" deps-clr)
  (write-config! dir "magic.edn" magic)
  (let [base   (vec (choose-tasks (present-configs dir) tasks))
        chosen (if-let [t (cljr-test-task (read-deps-clr dir) cljr)] (conj base t) base)]
    (println (str "  tasks: " (str/join ", " (map task->label chosen))))
    (mapv #(run-task! dir label %) chosen)))

(defn- run-entry!
  "Clone the lib, inject deps-clr.edn/magic.edn where it ships none, run its
  nos tasks plus cljr -X:test when available, and return its result map.
  A monorepo runs one directory per component: those discovered under the clone
  plus any the entry's :components names, each with that key's own config."
  [[k {:keys [components] :git/keys [url ref] :as entry}]]
  (println (str "\n== " k " =="))
  (let [dir      (fs/file "work" (slug k))
        used-ref (clone! url (or ref default-ref) dir)
        subdirs  (sort (distinct (concat (keys components) (discover-components dir))))
        results  (if (seq subdirs)
                   (vec (mapcat (fn [sub]
                                  (println (str "\n  -- " sub " --"))
                                  (run-dir! (fs/file dir sub) (str sub ": ") (get components sub)))
                                subdirs))
                   (run-dir! dir "" entry))]
    (println)
    (doseq [{:keys [task ok exit]} results]
      (println (format "  %-32s %s (exit %d)" task (if ok "PASS" "FAIL") exit)))
    {:lib   k
     :ok    (every? :ok results)
     :ref   used-ref
     :sha   (head-sha dir)
     :spec  (spec-hash entry)
     :tasks results}))

(defn- record-of [{:keys [ok ref sha spec tasks]}]
  {:ok ok :ref ref :sha sha :spec spec :tasks tasks})

(defn- read-results! []
  (if (fs/exists? results-file) (edn/read-string (slurp results-file)) {}))

(defn- write-results! [magic libs]
  (let [libs* (into (sorted-map-by #(compare (str %1) (str %2))) libs)
        pass  (count (filter (comp :ok val) libs*))
        total (count libs*)]
    (spit results-file
          (with-out-str
            (pprint {:magic-version magic
                     :total         total
                     :pass          pass
                     :fail          (- total pass)
                     :libs          libs*})))
    (println (format "\nwrote %s (%d libs: %d pass, %d fail; MAGIC %s)"
                     results-file total pass (- total pass) magic))))

(defn- safe-run
  "run-entry! for [k entry], turning a thrown error into a failed record."
  [[k {:git/keys [ref] :as conf} :as entry]]
  (try (run-entry! entry)
       (catch Exception e
         (println (str "  ERROR: " (ex-message e)))
         {:lib k :ok false :ref (or ref default-ref) :sha nil
          :spec (spec-hash conf) :tasks []})))

(defn- print-summary [title libs]
  (println (str "\n=== " title " ==="))
  (doseq [[k rec] (sort-by (comp str key) libs)]
    (println (format "  %-34s %s" (str k) (if (:ok rec) "PASS" "FAIL")))))

(defn check!
  "Check one lib, merge it into results.edn, and exit non-zero on failure."
  [sel]
  (let [manifest (read-manifest!)]
    (if-let [entry (and sel (resolve-lib manifest sel))]
      (let [prior (read-results!)
            res   (run-entry! entry)
            libs  (assoc (:libs prior) (:lib res) (record-of res))]
        (write-results! magic-version libs)
        (println (str (if (:ok res) "\nOK: " "\nFAILED: ") (:lib res)))
        (System/exit (if (:ok res) 0 1)))
      (do (println (str "usage: bb check <lib>; known: "
                        (str/join ", " (map str (keys manifest)))))
          (System/exit 2)))))

(defn check-all!
  "Check every lib and write results.edn, skipping libs whose SHA + MAGIC version
  + spec are unchanged. force? re-runs everything."
  [force?]
  (let [manifest    (read-manifest!)
        prior       (read-results!)
        cur-magic   magic-version
        prior-magic (:magic-version prior)
        libs (reduce
              (fn [acc [k {:git/keys [url ref] :as entry}]]
                (let [ref*  (or ref default-ref)
                      spec  (spec-hash entry)
                      prev  (get-in prior [:libs k])]
                  (if (and (cacheable? force? prior-magic cur-magic prev spec)
                           (when-let [sha (remote-sha url ref*)] (= (:sha prev) sha)))
                    (do (println (format "\n== %s ==\n  skip (cached %s, %s @ %s unchanged)"
                                         k (if (:ok prev) "PASS" "FAIL") ref* (short-sha (:sha prev))))
                        (assoc acc k prev))
                    (assoc acc k (record-of (safe-run [k entry]))))))
              {} manifest)]
    (write-results! cur-magic libs)
    (print-summary "summary" libs)
    (System/exit (if (every? :ok (vals libs)) 0 1))))
