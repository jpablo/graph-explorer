package org.jpablo.graphexplorer.viewer.state

import scala.scalajs.js

object TestSetup {
  def setupMockStorage(): Unit =
    js.eval(
      """
        if (typeof window === 'undefined') {
          global.window = {};
        }

        // Always set up mock localStorage and sessionStorage for tests
        if (!window.__mockStorageInitialized) {
          var localStorage = {};
          var sessionStorage = {};

          window.__testLocalStorage = localStorage;
          window.__testSessionStorage = sessionStorage;
          window.__mockStorageInitialized = true;

          function createStorage(storage) {
            return {
              getItem: function(key) {
                return storage[key] || null;
              },
              setItem: function(key, value) {
                storage[key] = value.toString();
              },
              removeItem: function(key) {
                delete storage[key];
              },
              clear: function() {
                for (var key in storage) {
                  delete storage[key];
                }
              },
              get length() {
                return Object.keys(storage).length;
              },
              key: function(index) {
                var keys = Object.keys(storage);
                return keys[index] || null;
              }
            };
          }

          Object.defineProperty(window, 'localStorage', {
            value: createStorage(localStorage),
            writable: true,
            configurable: true
          });

          Object.defineProperty(window, 'sessionStorage', {
            value: createStorage(sessionStorage),
            writable: true,
            configurable: true
          });
        } else {
          window.localStorage.clear();
          window.sessionStorage.clear();
        }
      """
    )

  def cleanupMockStorage(): Unit =
    js.eval(
      """
        if (typeof window !== 'undefined' && window.localStorage && window.localStorage.clear) {
          try {
            window.localStorage.clear();
          } catch (e) {
            // Ignore errors from inaccessible localStorage
          }
        }
      """
    )
}
