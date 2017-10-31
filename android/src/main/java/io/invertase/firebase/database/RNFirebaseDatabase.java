package io.invertase.firebase.database;

import android.os.AsyncTask;
import android.util.SparseArray;

import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;

import com.google.firebase.FirebaseApp;
import com.google.firebase.database.MutableData;
import com.google.firebase.database.OnDisconnect;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.Transaction;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.DatabaseReference;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.invertase.firebase.ErrorUtils;
import io.invertase.firebase.Utils;


public class RNFirebaseDatabase extends ReactContextBaseJavaModule {
  private static final String TAG = "RNFirebaseDatabase";
  private HashMap<String, RNFirebaseDatabaseReference> references = new HashMap<>();
  private SparseArray<RNFirebaseTransactionHandler> transactionHandlers = new SparseArray<>();

  RNFirebaseDatabase(ReactApplicationContext reactContext) {
    super(reactContext);
  }


  /*
   * REACT NATIVE METHODS
   */

  /**
   * @param appName
   */
  @ReactMethod
  public void goOnline(String appName) {
    getDatabaseForApp(appName).goOnline();
  }

  /**
   * @param appName
   */
  @ReactMethod
  public void goOffline(String appName) {
    getDatabaseForApp(appName).goOffline();
  }

  /**
   * @param appName
   * @param state
   */
  @ReactMethod
  public void setPersistence(String appName, Boolean state) {
    getDatabaseForApp(appName).setPersistenceEnabled(state);
  }

  /**
   * @param appName
   * @param path
   * @param state
   */
  @ReactMethod
  public void keepSynced(String appName, String key, String path, ReadableArray modifiers, Boolean state) {
    getInternalReferenceForApp(appName, key, path, modifiers).getQuery().keepSynced(state);
  }


  /*
   * TRANSACTIONS
   */

  /**
   * @param transactionId
   * @param updates
   */
  @ReactMethod
  public void transactionTryCommit(String appName, int transactionId, ReadableMap updates) {
    RNFirebaseTransactionHandler handler = transactionHandlers.get(transactionId);

    if (handler != null) {
      handler.signalUpdateReceived(updates);
    }
  }

  /**
   * Start a native transaction and store it's state in
   *
   * @param appName
   * @param path
   * @param transactionId
   * @param applyLocally
   */
  @ReactMethod
  public void transactionStart(final String appName, final String path, final int transactionId, final Boolean applyLocally) {
    AsyncTask.execute(new Runnable() {
      @Override
      public void run() {
        DatabaseReference reference = getReferenceForAppPath(appName, path);

        reference.runTransaction(new Transaction.Handler() {
          @Override
          public Transaction.Result doTransaction(MutableData mutableData) {
            final RNFirebaseTransactionHandler transactionHandler = new RNFirebaseTransactionHandler(transactionId, appName);
            transactionHandlers.put(transactionId, transactionHandler);
            final WritableMap updatesMap = transactionHandler.createUpdateMap(mutableData);

            // emit the updates to js using an async task
            // otherwise it gets blocked by the lock await
            AsyncTask.execute(new Runnable() {
              @Override
              public void run() {
                Utils.sendEvent(getReactApplicationContext(), "database_transaction_event", updatesMap);
              }
            });

            // wait for js to return the updates (js calls transactionTryCommit)
            try {
              transactionHandler.await();
            } catch (InterruptedException e) {
              transactionHandler.interrupted = true;
              return Transaction.abort();
            }

            if (transactionHandler.abort) {
              return Transaction.abort();
            }

            mutableData.setValue(transactionHandler.value);
            return Transaction.success(mutableData);
          }

          @Override
          public void onComplete(DatabaseError error, boolean committed, DataSnapshot snapshot) {
            RNFirebaseTransactionHandler transactionHandler = transactionHandlers.get(transactionId);
            WritableMap resultMap = transactionHandler.createResultMap(error, committed, snapshot);
            Utils.sendEvent(getReactApplicationContext(), "database_transaction_event", resultMap);
            transactionHandlers.delete(transactionId);
          }

        }, applyLocally);
      }
    });
  }


  /*
   * ON DISCONNECT
   */

  /**
   * Set a value on a ref when the client disconnects from the firebase server.
   *
   * @param appName
   * @param path
   * @param props
   * @param promise
   */
  @ReactMethod
  public void onDisconnectSet(String appName, String path, ReadableMap props, final Promise promise) {
    String type = props.getString("type");
    DatabaseReference ref = getReferenceForAppPath(appName, path);

    OnDisconnect onDisconnect = ref.onDisconnect();
    DatabaseReference.CompletionListener listener = new DatabaseReference.CompletionListener() {
      @Override
      public void onComplete(DatabaseError error, DatabaseReference ref) {
        handlePromise(promise, error);
      }
    };

    switch (type) {
      case "object":
        Map<String, Object> map = Utils.recursivelyDeconstructReadableMap(props.getMap("value"));
        onDisconnect.setValue(map, listener);
        break;
      case "array":
        List<Object> list = Utils.recursivelyDeconstructReadableArray(props.getArray("value"));
        onDisconnect.setValue(list, listener);
        break;
      case "string":
        onDisconnect.setValue(props.getString("value"), listener);
        break;
      case "number":
        onDisconnect.setValue(props.getDouble("value"), listener);
        break;
      case "boolean":
        onDisconnect.setValue(props.getBoolean("value"), listener);
        break;
      case "null":
        onDisconnect.setValue(null, listener);
        break;
    }
  }

  /**
   * Update a value on a ref when the client disconnects from the firebase server.
   *
   * @param appName
   * @param path
   * @param props
   * @param promise
   */
  @ReactMethod
  public void onDisconnectUpdate(String appName, String path, ReadableMap props, final Promise promise) {
    DatabaseReference ref = getReferenceForAppPath(appName, path);
    OnDisconnect ondDisconnect = ref.onDisconnect();

    Map<String, Object> map = Utils.recursivelyDeconstructReadableMap(props);

    ondDisconnect.updateChildren(map, new DatabaseReference.CompletionListener() {
      @Override
      public void onComplete(DatabaseError error, DatabaseReference ref) {
        handlePromise(promise, error);
      }
    });
  }

  /**
   * Remove a ref when the client disconnects from the firebase server.
   *
   * @param appName
   * @param path
   * @param promise
   */
  @ReactMethod
  public void onDisconnectRemove(String appName, String path, final Promise promise) {
    DatabaseReference ref = getReferenceForAppPath(appName, path);
    OnDisconnect onDisconnect = ref.onDisconnect();

    onDisconnect.removeValue(new DatabaseReference.CompletionListener() {
      @Override
      public void onComplete(DatabaseError error, DatabaseReference ref) {
        handlePromise(promise, error);
      }
    });
  }

  /**
   * Cancel a pending onDisconnect action.
   *
   * @param appName
   * @param path
   * @param promise
   */
  @ReactMethod
  public void onDisconnectCancel(String appName, String path, final Promise promise) {
    DatabaseReference ref = getReferenceForAppPath(appName, path);
    OnDisconnect onDisconnect = ref.onDisconnect();

    onDisconnect.cancel(new DatabaseReference.CompletionListener() {
      @Override
      public void onComplete(DatabaseError error, DatabaseReference ref) {
        handlePromise(promise, error);
      }
    });
  }

  /**
   * @param appName
   * @param path
   * @param props
   * @param promise
   */
  @ReactMethod
  public void set(String appName, String path, ReadableMap props, final Promise promise) {
    DatabaseReference ref = getReferenceForAppPath(appName, path);
    Object value = Utils.recursivelyDeconstructReadableMap(props).get("value");

    DatabaseReference.CompletionListener listener = new DatabaseReference.CompletionListener() {
      @Override
      public void onComplete(DatabaseError error, DatabaseReference ref) {
        handlePromise(promise, error);
      }
    };

    ref.setValue(value, listener);
  }

  /**
   * @param appName
   * @param path
   * @param priority
   * @param promise
   */
  @ReactMethod
  public void setPriority(String appName, String path, ReadableMap priority, final Promise promise) {
    DatabaseReference ref = getReferenceForAppPath(appName, path);
    Object priorityValue = Utils.recursivelyDeconstructReadableMap(priority).get("value");

    DatabaseReference.CompletionListener listener = new DatabaseReference.CompletionListener() {
      @Override
      public void onComplete(DatabaseError error, DatabaseReference ref) {
        handlePromise(promise, error);
      }
    };

    ref.setPriority(priorityValue, listener);
  }

  /**
   * @param appName
   * @param path
   * @param data
   * @param priority
   * @param promise
   */
  @ReactMethod
  public void setWithPriority(String appName, String path, ReadableMap data, ReadableMap priority, final Promise promise) {
    DatabaseReference ref = getReferenceForAppPath(appName, path);
    Object dataValue = Utils.recursivelyDeconstructReadableMap(data).get("value");
    Object priorityValue = Utils.recursivelyDeconstructReadableMap(priority).get("value");

    DatabaseReference.CompletionListener listener = new DatabaseReference.CompletionListener() {
      @Override
      public void onComplete(DatabaseError error, DatabaseReference ref) {
        handlePromise(promise, error);
      }
    };

    ref.setValue(dataValue, priorityValue, listener);
  }

  /**
   * @param appName
   * @param path
   * @param props
   * @param promise
   */
  @ReactMethod
  public void update(String appName, String path, ReadableMap props, final Promise promise) {
    DatabaseReference ref = getReferenceForAppPath(appName, path);
    Map<String, Object> updates = Utils.recursivelyDeconstructReadableMap(props);

    DatabaseReference.CompletionListener listener = new DatabaseReference.CompletionListener() {
      @Override
      public void onComplete(DatabaseError error, DatabaseReference ref) {
        handlePromise(promise, error);
      }
    };

    ref.updateChildren(updates, listener);
  }

  /**
   * @param appName
   * @param path
   * @param promise
   */
  @ReactMethod
  public void remove(String appName, String path, final Promise promise) {
    DatabaseReference ref = getReferenceForAppPath(appName, path);

    DatabaseReference.CompletionListener listener = new DatabaseReference.CompletionListener() {
      @Override
      public void onComplete(DatabaseError error, DatabaseReference ref) {
        handlePromise(promise, error);
      }
    };

    ref.removeValue(listener);
  }


  /**
   * Subscribe once to a firebase reference.
   *
   * @param appName
   * @param key
   * @param path
   * @param modifiers
   * @param eventType
   * @param promise
   */
  @ReactMethod
  public void once(String appName, String key, String path, ReadableArray modifiers, String eventType, Promise promise) {
    getInternalReferenceForApp(appName, key, path, modifiers).once(eventType, promise);
  }

  /**
   * Subscribe to real time events for the specified database path + modifiers
   *
   * @param appName String
   * @param props   ReadableMap
   */
  @ReactMethod
  public void on(String appName, ReadableMap props) {
    getCachedInternalReferenceForApp(appName, props)
      .on(
        props.getString("eventType"),
        props.getMap("registration")
      );
  }

  /**
   * Removes the specified event registration key.
   * If the ref no longer has any listeners the the ref is removed.
   *
   * @param key
   * @param eventRegistrationKey
   */
  @ReactMethod
  public void off(String key, String eventRegistrationKey) {
    RNFirebaseDatabaseReference nativeRef = references.get(key);
    if (nativeRef != null) {
      nativeRef.removeEventListener(eventRegistrationKey);

      if (!nativeRef.hasListeners()) {
        references.remove(key);
      }
    }
  }

  /*
   * INTERNALS/UTILS
   */

  /**
   * Resolve null or reject with a js like error if databaseError exists
   *
   * @param promise
   * @param databaseError
   */
  static void handlePromise(Promise promise, DatabaseError databaseError) {
    if (databaseError != null) {
      WritableMap jsError = getJSError(databaseError);
      promise.reject(
        jsError.getString("code"),
        jsError.getString("message"),
        databaseError.toException()
      );
    } else {
      promise.resolve(null);
    }
  }


  /**
   * Get a database instance for a specific firebase app instance
   *
   * @param appName
   * @return
   */
  static FirebaseDatabase getDatabaseForApp(String appName) {
    FirebaseApp firebaseApp = FirebaseApp.getInstance(appName);
    return FirebaseDatabase.getInstance(firebaseApp);
  }

  /**
   * Get a database reference for a specific app and path
   *
   * @param appName
   * @param path
   * @return
   */
  private DatabaseReference getReferenceForAppPath(String appName, String path) {
    return getDatabaseForApp(appName).getReference(path);
  }

  /**
   * Return an existing or create a new RNFirebaseDatabaseReference instance.
   *
   * @param appName
   * @param key
   * @param path
   * @param modifiers
   * @return
   */
  private RNFirebaseDatabaseReference getInternalReferenceForApp(String appName, String key, String path, ReadableArray modifiers) {
    return new RNFirebaseDatabaseReference(
      getReactApplicationContext(),
      appName,
      key,
      path,
      modifiers
    );
  }

  /**
   * TODO
   *
   * @param appName
   * @param props
   * @return
   */
  private RNFirebaseDatabaseReference getCachedInternalReferenceForApp(String appName, ReadableMap props) {
    String key = props.getString("key");
    String path = props.getString("path");
    ReadableArray modifiers = props.getArray("modifiers");

    RNFirebaseDatabaseReference existingRef = references.get(key);

    if (existingRef == null) {
      existingRef = getInternalReferenceForApp(appName, key, path, modifiers);
      references.put(key, existingRef);
    }

    return existingRef;
  }

  /**
   * Convert as firebase DatabaseError instance into a writable map
   * with the correct web-like error codes.
   *
   * @param nativeError
   * @return
   */
  static WritableMap getJSError(DatabaseError nativeError) {
    WritableMap errorMap = Arguments.createMap();
    errorMap.putInt("nativeErrorCode", nativeError.getCode());
    errorMap.putString("nativeErrorMessage", nativeError.getMessage());

    String code;
    String message;
    String service = "Database";

    switch (nativeError.getCode()) {
      case DatabaseError.DATA_STALE:
        code = ErrorUtils.getCodeWithService(service, "data-stale");
        message = ErrorUtils.getMessageWithService("The transaction needs to be run again with current data.", service, code);
        break;
      case DatabaseError.OPERATION_FAILED:
        code = ErrorUtils.getCodeWithService(service, "failure");
        message = ErrorUtils.getMessageWithService("The server indicated that this operation failed.", service, code);
        break;
      case DatabaseError.PERMISSION_DENIED:
        code = ErrorUtils.getCodeWithService(service, "permission-denied");
        message = ErrorUtils.getMessageWithService("Client doesn't have permission to access the desired data.", service, code);
        break;
      case DatabaseError.DISCONNECTED:
        code = ErrorUtils.getCodeWithService(service, "disconnected");
        message = ErrorUtils.getMessageWithService("The operation had to be aborted due to a network disconnect.", service, code);
        break;
      case DatabaseError.EXPIRED_TOKEN:
        code = ErrorUtils.getCodeWithService(service, "expired-token");
        message = ErrorUtils.getMessageWithService("The supplied auth token has expired.", service, code);
        break;
      case DatabaseError.INVALID_TOKEN:
        code = ErrorUtils.getCodeWithService(service, "invalid-token");
        message = ErrorUtils.getMessageWithService("The supplied auth token was invalid.", service, code);
        break;
      case DatabaseError.MAX_RETRIES:
        code = ErrorUtils.getCodeWithService(service, "max-retries");
        message = ErrorUtils.getMessageWithService("The transaction had too many retries.", service, code);
        break;
      case DatabaseError.OVERRIDDEN_BY_SET:
        code = ErrorUtils.getCodeWithService(service, "overridden-by-set");
        message = ErrorUtils.getMessageWithService("The transaction was overridden by a subsequent set.", service, code);
        break;
      case DatabaseError.UNAVAILABLE:
        code = ErrorUtils.getCodeWithService(service, "unavailable");
        message = ErrorUtils.getMessageWithService("The service is unavailable.", service, code);
        break;
      case DatabaseError.USER_CODE_EXCEPTION:
        code = ErrorUtils.getCodeWithService(service, "user-code-exception");
        message = ErrorUtils.getMessageWithService("User code called from the Firebase Database runloop threw an exception.", service, code);
        break;
      case DatabaseError.NETWORK_ERROR:
        code = ErrorUtils.getCodeWithService(service, "network-error");
        message = ErrorUtils.getMessageWithService("The operation could not be performed due to a network error.", service, code);
        break;
      case DatabaseError.WRITE_CANCELED:
        code = ErrorUtils.getCodeWithService(service, "write-cancelled");
        message = ErrorUtils.getMessageWithService("The write was canceled by the user.", service, code);
        break;
      default:
        code = ErrorUtils.getCodeWithService(service, "unknown");
        message = ErrorUtils.getMessageWithService("An unknown error occurred.", service, code);
    }

    errorMap.putString("code", code);
    errorMap.putString("message", message);
    return errorMap;
  }

  /**
   * React Method - returns this module name
   *
   * @return
   */
  @Override
  public String getName() {
    return "RNFirebaseDatabase";
  }

  /**
   * React Native constants for RNFirebaseDatabase
   *
   * @return
   */
  @Override
  public Map<String, Object> getConstants() {
    final Map<String, Object> constants = new HashMap<>();
    constants.put("serverValueTimestamp", ServerValue.TIMESTAMP);
    return constants;
  }
}
