(ns pallet.action.remote-file-test
  (:use pallet.action.remote-file)
  (:use [pallet.stevedore :only [script]]
        clojure.test)
  (:require
   [pallet.action :as action]
   [pallet.build-actions :as build-actions]
   [pallet.common.logging.log4j :as log4j]
   [pallet.compute :as compute]
   [pallet.core :as core]
   [pallet.execute :as execute]
   [pallet.phase :as phase]
   [pallet.action.exec-script :as exec-script]
   [pallet.action.file :as file]
   [pallet.script :as script]
   [pallet.script.lib :as lib]
   [pallet.stevedore :as stevedore]
   [pallet.target :as target]
   [pallet.test-utils :as test-utils]
   [pallet.utils :as utils]
   [clojure.contrib.io :as io]))

(use-fixtures
 :once
 test-utils/with-ubuntu-script-template
 (log4j/logging-threshold-fixture))

(def remote-file* (action/action-fn remote-file-action))

(deftest remote-file*-test
  (is remote-file*)
  (testing "url"
    (is (= (stevedore/checked-commands
            "remote-file path"
            (stevedore/chained-script
             (~lib/download-file "http://a.com/b" "path.new")
             (if (file-exists? "path.new")
               (do
                 (mv -f "path.new" path)))))
           (remote-file* {} "path" :url "http://a.com/b" :no-versioning true))))
  (testing "url with proxy"
    (is (= (stevedore/checked-commands
            "remote-file path"
            (stevedore/chained-script
             (~lib/download-file
              "http://a.com/b" "path.new" :proxy "http://proxy/")
             (if (file-exists? "path.new")
               (do
                 (mv -f "path.new" path)))))
           (remote-file*
            {:environment {:proxy "http://proxy/"}}
            "path" :url "http://a.com/b" :no-versioning true))))

  (testing "no-versioning"
    (is (= (stevedore/checked-commands
            "remote-file path"
            (stevedore/script (~lib/heredoc "path.new" "xxx" {}))
            (stevedore/chained-script
             (if (file-exists? "path.new")
               (do
                 (~lib/mv "path.new" path :force true)))))
           (remote-file* {} "path" :content "xxx" :no-versioning true))))

  (testing "no-versioning with owner, group and mode"
    (is (= (stevedore/checked-commands
            "remote-file path"
            (stevedore/script (~lib/heredoc "path.new" "xxx" {}))
            (stevedore/chained-script
             (if (file-exists? "path.new")
               (do
                 (~lib/mv "path.new" "path" :force true)))
             (~lib/chown "o" "path")
             (~lib/chgrp "g" "path")
             (~lib/chmod "m" "path")))
           (remote-file*
            {} "path" :content "xxx" :owner "o" :group "g" :mode "m"
            :no-versioning true))))

  (testing "delete"
    (is (= (stevedore/checked-script
            "delete remote-file path"
            ("rm" "--force" "path"))
           (remote-file* {} "path" :action :delete :force true))))

  (testing "content"
    (utils/with-temporary [tmp (utils/tmpfile)]
      (.delete tmp)
      (is (= (str "remote-file " (.getPath tmp) "...\n"
                  "MD5 sum is 6de9439834c9147569741d3c9c9fc010 "
                  (.getPath tmp) "\n"
                  "...done\n")
             (->
              (pallet.core/lift
               {{:group-name :local} (test-utils/make-localhost-node)}
               :phase #(remote-file % (.getPath tmp) :content "xxx")
               :compute nil
               :middleware [core/translate-action-plan
                            execute/execute-target-on-localhost])
              :results :localhost second second first :out)))
      (is (= "xxx\n" (slurp (.getPath tmp))))))

  (testing "overwrite on existing content and no md5"
    (utils/with-temporary [tmp (utils/tmpfile)]
      (is (re-matches
           (java.util.regex.Pattern/compile
            (str "remote-file .*...done.")
            (bit-or java.util.regex.Pattern/MULTILINE
                    java.util.regex.Pattern/DOTALL))
           (->
            (pallet.core/lift
             {{:group-name :local} (test-utils/make-localhost-node)}
             :phase #(remote-file % (.getPath tmp) :content "xxx")
             :compute nil
             :middleware [core/translate-action-plan
                          execute/execute-target-on-localhost])
            :results :localhost second second first :out)))
      (is (= "xxx\n" (slurp (.getPath tmp))))))

  (binding [install-new-files nil]
    (script/with-template [:ubuntu]
      (is (=
           (stevedore/checked-script
            "remote-file path"
            (~lib/heredoc "path.new" "a 1\n" {}))
           (remote-file*
            {:server {:group-name :n :image {:os-family :ubuntu}}}
            "path" :template "template/strint" :values {'a 1}
            :no-versioning true))))))

(deftest remote-file-test
  (core/with-admin-user
    (assoc utils/*admin-user* :username (test-utils/test-username))
    (is (thrown-with-msg? RuntimeException
          #".*/some/non-existing/file.*does not exist, is a directory, or is unreadable.*"
          (build-actions/build-actions
           {} (remote-file
               "file1" :local-file "/some/non-existing/file" :owner "user1"))))

    (is (thrown-with-msg? RuntimeException
          #".*file1.*without content.*"
          (build-actions/build-actions
           {} (remote-file "file1" :owner "user1"))))

    (utils/with-temporary [tmp (utils/tmpfile)]
      (is (re-find #"mv -f --backup=\"numbered\" file1.new file1"
                   (first
                    (build-actions/build-actions
                     {} (remote-file
                         "file1" :local-file (.getPath tmp)))))))

    (utils/with-temporary [tmp (utils/tmpfile)
                           target-tmp (utils/tmpfile)]
      ;; this is convoluted to get around the "t" sticky bit on temp dirs
      (let [user (assoc utils/*admin-user*
                   :username (test-utils/test-username) :no-sudo true)]
        (.delete target-tmp)
        (io/copy "text" tmp)
        (let [local (core/make-node "local" {})
              node (test-utils/make-localhost-node :group-name "local")]
          (testing "local-file"
            (core/lift
             {local node}
             :phase #(remote-file
                      % (.getPath target-tmp) :local-file (.getPath tmp)
                      :mode "0666")
             :user user)
            (is (.canRead target-tmp))
            (is (= "text" (slurp (.getPath target-tmp)))))
          (testing "content"
            (core/lift
             {local node}
             :phase #(remote-file
                      % (.getPath target-tmp) :content "$(hostname)"
                      :mode "0666" :flag-on-changed :changed)
             :user user)
            (is (.canRead target-tmp))
            (is (= (:out (execute/sh-script "hostname"))
                   (slurp (.getPath target-tmp)))))
          (testing "content unchanged"
            (is
             (re-find
              #"correctly unchanged"
              (->
               (core/lift
                {local node}
                :phase (phase/phase-fn
                        (remote-file
                         (.getPath target-tmp) :content "$(hostname)"
                         :mode "0666" :flag-on-changed :changed)
                        (exec-script/exec-script
                         (if (== (~lib/flag? :changed) "1")
                           (println "incorrect!" (~lib/flag? :changed) "!")
                           (println "correctly unchanged"))))
                :user user)
               :results :localhost second second first :out)))
            (is (.canRead target-tmp))
            (is (= (:out (execute/sh-script "hostname"))
                   (slurp (.getPath target-tmp)))))
          (testing "content changed"
            (is
             (re-find
              #"correctly changed"
              (->
               (core/lift
                {local node}
                :phase (phase/phase-fn
                        (remote-file
                         (.getPath target-tmp) :content "abc"
                         :mode "0666" :flag-on-changed :changed)
                        (exec-script/exec-script
                         (if (== (~lib/flag? :changed) "1")
                           (println "correctly changed")
                           (println "incorrect!" (~lib/flag? :changed) "!"))))
                :user user)
               :results :localhost second second first :out)))
            (is (.canRead target-tmp))
            (is (= "abc\n"
                   (slurp (.getPath target-tmp)))))
          (testing "content"
            (core/lift
             {local node}
             :phase #(remote-file
                      % (.getPath target-tmp) :content "$text123" :literal true
                      :mode "0666")
             :user user)
            (is (.canRead target-tmp))
            (is (= "$text123\n" (slurp (.getPath target-tmp)))))
          (testing "remote-file"
            (io/copy "text" tmp)
            (core/lift
             {local node}
             :phase #(remote-file
                      % (.getPath target-tmp) :remote-file (.getPath tmp)
                      :mode "0666")
             :user user)
            (is (.canRead target-tmp))
            (is (= "text" (slurp (.getPath target-tmp)))))
          (testing "url"
            (io/copy "urltext" tmp)
            (core/lift
             {local node}
             :phase #(remote-file
                      % (.getPath target-tmp)
                      :url (str "file://" (.getPath tmp))
                      :mode "0666")
             :user user)
            (is (.canRead target-tmp))
            (is (= "urltext" (slurp (.getPath target-tmp)))))
          (testing "url with md5"
            (io/copy "urlmd5text" tmp)
            (core/lift
             {local node}
             :phase #(remote-file
                      % (.getPath target-tmp)
                      :url (str "file://" (.getPath tmp))
                      :md5 (stevedore/script @(~lib/md5sum ~(.getPath tmp)))
                      :mode "0666")
             :user user)
            (is (.canRead target-tmp))
            (is (= "urlmd5text" (slurp (.getPath target-tmp)))))
          (testing "url with md5 urls"
            (.delete target-tmp)
            (io/copy "urlmd5urltext" tmp)
            (let [md5path (str (.getPath tmp) ".md5")]
              (core/lift
               {local node}
               :phase (phase/phase-fn
                       (exec-script/exec-script
                        ((~lib/md5sum ~(.getPath tmp)) > ~md5path))
                       (remote-file
                        (.getPath target-tmp)
                        :url (str "file://" (.getPath tmp))
                        :md5-url (str "file://" md5path)
                        :mode "0666"))
               :user user))
            (is (.canRead target-tmp))
            (is (= "urlmd5urltext" (slurp (.getPath target-tmp)))))
          (testing "delete action"
            (.createNewFile target-tmp)
            (core/lift
             {local node}
             :phase #(remote-file % (.getPath target-tmp) :action :delete)
             :user user)
            (is (not (.exists target-tmp)))))))))

(action/def-clj-action check-content
  [session path content path-atom]
  (is (= content (slurp path)))
  (reset! path-atom path))

(deftest with-remote-file-test
  (core/with-admin-user (assoc utils/*admin-user*
                          :username (test-utils/test-username))
    (utils/with-temporary [remote-file (utils/tmpfile)]
      (let [user (assoc utils/*admin-user*
                   :username (test-utils/test-username) :no-sudo true)
            local (core/group-spec "local")]
        (io/copy "text" remote-file)
        (testing "with local ssh"
          (let [node (test-utils/make-localhost-node)
                path-atom (atom nil)]
            (testing "with-remote-file"
              (core/lift
               {local node}
               :phase #(with-remote-file
                         % check-content (.getPath remote-file)
                         "text" path-atom)
               :user user)
              (is @path-atom)
              (is (not= (.getPath remote-file) (.getPath @path-atom))))))
        (testing "with local shell"
          (let [node (test-utils/make-localhost-node)
                path-atom (atom nil)]
            (testing "with-remote-file"
              (core/lift
               {local node}
               :phase #(with-remote-file
                         % check-content (.getPath remote-file)
                         "text" path-atom)
               :user user
               :middleware [core/translate-action-plan
                            execute/execute-target-on-localhost])
              (is @path-atom)
              (is (not= (.getPath remote-file) (.getPath @path-atom))))))))))
