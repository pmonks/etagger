;
; Copyright © 2023 Peter Monks
;
; Licensed under the Apache License Version 2.0 (the "License");
; you may not use this file except in compliance with the License.
; You may obtain a copy of the License at
;
;     http://www.apache.org/licenses/LICENSE-2.0
;
; Unless required by applicable law or agreed to in writing software
; distributed under the License is distributed on an "AS IS" BASIS,
; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND either express or implied.
; See the License for the specific language governing permissions and
; limitations under the License.
;
; SPDX-License-Identifier: Apache-2.0
;

(ns urlocal.api-test
  (:require [urlocal.impl.cache :as uic]
            [clojure.test       :refer [deftest testing is]]
            [urlocal.api        :refer [set-cache-name! reset-cache! remove-cache-entry! set-cache-check-interval-secs! input-stream]]))

(set-cache-name! "urlocal-tests")
(reset-cache!)

(defn valid-cached-response?
  [url is]
  (and (not (nil? is))
       (instance? java.io.InputStream is)
       (.exists (uic/url->content-file  url))
       (.exists (uic/url->metadata-file url))))

(deftest input-stream-tests
  (testing "nil, blank, etc."
    (is (nil?                                   (input-stream nil)))
    (is (thrown? java.net.MalformedURLException (input-stream ""))))
  (testing "Invalid URLs"
    (is (thrown? java.io.IOException            (input-stream "http://INVALID_HOST_THAT_DOES_NOT_EXIST.local/"))))
  (testing "Valid URLs - cache miss"
    (is (valid-cached-response? "https://spdx.org/licenses/licenses.json" (input-stream "https://spdx.org/licenses/licenses.json"))))
  (testing "Valid URLs - within cache interval period"
    (is (valid-cached-response? "https://spdx.org/licenses/licenses.json" (input-stream "https://spdx.org/licenses/licenses.json"))))
  (testing "Valid URLs - outside cache interval period, but cache hit"
    (set-cache-check-interval-secs! 0)
    (Thread/sleep 1000)  ; Make sure we have at least a once second gap between cache checks
    (is (valid-cached-response? "https://spdx.org/licenses/licenses.json" (input-stream "https://spdx.org/licenses/licenses.json")))))
