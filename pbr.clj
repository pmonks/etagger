;
; Copyright © 2023 Peter Monks
;
; Licensed under the Apache License, Version 2.0 (the "License");
; you may not use this file except in compliance with the License.
; You may obtain a copy of the License at
;
;     http://www.apache.org/licenses/LICENSE-2.0
;
; Unless required by applicable law or agreed to in writing, software
; distributed under the License is distributed on an "AS IS" BASIS,
; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
; See the License for the specific language governing permissions and
; limitations under the License.
;
; SPDX-License-Identifier: Apache-2.0
;

#_{:clj-kondo/ignore [:unresolved-namespace]}
(defn set-opts
  [opts]
  (assoc opts
         :lib          'com.github.pmonks/urlocal
         :version      (pbr/calculate-version 1 0)
         :write-pom    true
         :validate-pom true
         :pom          {:description      "A Clojure micro-library for cached (ETag based) URL downloads "
                        :url              "https://github.com/pmonks/urlocal"
                        :licenses         [:license   {:name "Apache License 2.0" :url "http://www.apache.org/licenses/LICENSE-2.0.html"}]
                        :developers       [:developer {:id "pmonks" :name "Peter Monks" :email "pmonks+urlocal@gmail.com"}]
                        :scm              {:url                  "https://github.com/pmonks/urlocal"
                                           :connection           "scm:git:git://github.com/pmonks/urlocal.git"
                                           :developer-connection "scm:git:ssh://git@github.com/pmonks/urlocal.git"
                                           :tag                  (tc/git-tag-or-hash)}
                        :issue-management {:system "github" :url "https://github.com/pmonks/urlocal/issues"}}
         :codox        {:namespaces ['urlocal.api]}))
