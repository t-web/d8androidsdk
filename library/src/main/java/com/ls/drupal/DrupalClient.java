/*
 * The MIT License (MIT)
 *  Copyright (c) 2014 Lemberg Solutions Limited
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *   The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *    THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *   IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *   FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *   AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *   LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *   OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *   SOFTWARE.
 */

package com.ls.drupal;

import com.android.volley.DefaultRetryPolicy;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.Volley;
import com.ls.drupal.login.AnonymousLoginManager;
import com.ls.drupal.login.ILoginManager;
import com.ls.http.base.BaseRequest;
import com.ls.http.base.BaseRequest.OnResponseListener;
import com.ls.http.base.BaseRequest.RequestFormat;
import com.ls.http.base.BaseRequest.RequestMethod;
import com.ls.http.base.ResponseData;
import com.ls.util.VolleyResponceUtils;

import android.content.Context;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import java.util.List;
import java.util.Map;

/**
 * Class is used to generate requests based on DrupalEntities and attach them to request queue
 *
 * @author lemberg
 */
public class DrupalClient implements OnResponseListener {

    private final RequestFormat requestFormat;
    private String baseURL;
    private RequestQueue queue;
    private ResponseListenersSet listeners;
    private String defaultCharset;

    private ILoginManager loginManager;
    private RequestProgressListener progressListener;

    private int requestTimeout = 1500;

    private boolean allowDuplicateRequests = false;

    public static interface OnResponseListener {

        void onResponseReceived(ResponseData data, Object tag);

        void onError(VolleyError error, Object tag);

        void onCancel(Object tag);
    }

    /**
     * Can be used in order to react on request count changes (start/success/failure or canceling).
     *
     * @author lemberg
     */
    public interface RequestProgressListener {

        /**
         * Called after new request was added to queue
         *
         * @param activeRequests number of requests pending
         */
        void onRequestStarted(DrupalClient theClient, int activeRequests);

        /**
         * Called after current request was complete
         *
         * @param activeRequests number of requests pending
         */
        void onRequestFinished(DrupalClient theClient, int activeRequests);
    }

    /**
     * @param theBaseURL this URL will be appended with {@link AbstractBaseDrupalEntity#getPath()}
     * @param theContext application context, used to create request queue
     */
    public DrupalClient(@NonNull String theBaseURL, @NonNull Context theContext) {
        this(theBaseURL, theContext, null);
    }

    /**
     * @param theBaseURL this URL will be appended with {@link AbstractBaseDrupalEntity#getPath()}
     * @param theContext application context, used to create request queue
     * @param theFormat  server request/response format. Defines format of serialized objects and server response format, see {@link com.ls.http.base.BaseRequest.RequestFormat}
     */
    public DrupalClient(@NonNull String theBaseURL, @NonNull Context theContext, @Nullable RequestFormat theFormat) {
        this(theBaseURL, theContext, theFormat, null);
    }

    /**
     * @param theBaseURL      this URL will be appended with {@link AbstractBaseDrupalEntity#getPath()}
     * @param theContext      application context, used to create request queue
     * @param theFormat       server request/response format. Defines format of serialized objects and server response format, see {@link com.ls.http.base.BaseRequest.RequestFormat}
     * @param theLoginManager contains user profile data and can update request parameters and headers in order to apply it.
     */
    public DrupalClient(@NonNull String theBaseURL, @NonNull Context theContext, @Nullable RequestFormat theFormat, @Nullable ILoginManager theLoginManager) {
        this(theBaseURL, getDefaultQueue(theContext), theFormat, theLoginManager);
    }

    @SuppressWarnings("null")
    private static
    @NonNull
    RequestQueue getDefaultQueue(@NonNull Context theContext) {
        return Volley.newRequestQueue(theContext.getApplicationContext());
    }

    /**
     * @param theBaseURL      this URL will be appended with {@link AbstractBaseDrupalEntity#getPath()}
     * @param theQueue        queue to execute requests. You can customize cache management, by setting custom queue
     * @param theFormat       server request/response format. Defines format of serialized objects and server response format, see {@link com.ls.http.base.BaseRequest.RequestFormat}
     * @param theLoginManager contains user profile data and can update request parameters and headers in order to apply it.
     */
    public DrupalClient(@NonNull String theBaseURL, @NonNull RequestQueue theQueue, @Nullable RequestFormat theFormat, @Nullable ILoginManager theLoginManager) {
        this.listeners = new ResponseListenersSet();
        this.queue = theQueue;
        this.setBaseURL(theBaseURL);

        if (theFormat != null) {
            this.requestFormat = theFormat;
        } else {
            this.requestFormat = RequestFormat.JSON;
        }

        if (theLoginManager != null) {
            this.setLoginManager(theLoginManager);
        } else {
            this.setLoginManager(new AnonymousLoginManager());
        }
    }

    /**
     * @param request     Request object to be performed
     * @param synchronous if true request result will be returned synchronously
     * @return {@link com.ls.http.base.ResponseData} object, containing request result code and string or error and deserialized object, specified in request.
     */
    public ResponseData performRequest(BaseRequest request, boolean synchronous) {
        return performRequest(request, null, null, synchronous);
    }

    /**
     * @param request     Request object to be performed
     * @param tag         will be applied to the request and returned in listener
     * @param synchronous if true request result will be returned synchronously
     * @return {@link com.ls.http.base.ResponseData} object, containing request result code and string or error and deserialized object, specified in request.
     */
    public ResponseData performRequest(BaseRequest request, Object tag, final OnResponseListener listener, boolean synchronous) {
        request.setRetryPolicy(new DefaultRetryPolicy(requestTimeout, 1, 1));
        if (!loginManager.shouldRestoreLogin()) {
            return performRequestNoLoginRestore(request, tag, listener, synchronous);
        } else {
            return performRequestLoginRestore(request, tag, listener, synchronous);
        }
    }

    protected ResponseData performRequestNoLoginRestore(BaseRequest request, Object tag, OnResponseListener listener, boolean synchronous) {
        request.setTag(tag);
        request.setResponseListener(this);
        this.loginManager.applyLoginDataToRequest(request);
        boolean wasREgisterred = this.listeners.registerListenerForRequest(request, listener);
        if(wasREgisterred||synchronous||this.allowDuplicateRequests) {
            this.onNewRequestStarted();
            return request.performRequest(synchronous, queue);
        }else{
            return null;
        }
    }

    private ResponseData performRequestLoginRestore(final BaseRequest request, Object tag, final OnResponseListener listener, final boolean synchronous) {
        if (synchronous) {
            return performRequestLoginRestoreSynchrounous(request, tag, listener);
        } else {
            return performRequestLoginRestoreAsynchrounous(request, tag, listener);
        }
    }

    private ResponseData performRequestLoginRestoreAsynchrounous(final BaseRequest request, Object tag, final OnResponseListener listener) {
        final OnResponseListener loginRestoreResponceListener = new OnResponseListener() {
            @Override
            public void onResponseReceived(ResponseData data, Object tag) {
                if (listener != null) {
                    listener.onResponseReceived(data, tag);
                }
            }

            @Override
            public void onError(VolleyError error, Object tag) {
                if (VolleyResponceUtils.isAuthError(error) ||
                        (loginManager.domainDependsOnLogin() && VolleyResponceUtils.isNotFoundError(error))) {
                    if (loginManager.canRestoreLogin()) {
                        new RestoreLoginAttemptTask(request, listener, tag, error).execute();
                    } else {
                        loginManager.onLoginRestoreFailed();
                        if (listener != null) {
                            listener.onError(error, tag);
                        }
                    }
                } else {
                    if (listener != null) {
                        listener.onError(error, tag);
                    }
                }
            }

            @Override
            public void onCancel(Object tag) {
                if (listener != null) {
                    listener.onCancel(tag);
                }
            }
        };

        return performRequestNoLoginRestore(request, tag, loginRestoreResponceListener, false);
    }

    private ResponseData performRequestLoginRestoreSynchrounous(final BaseRequest request, Object tag, final OnResponseListener listener) {
        final OnResponseListener loginRestoreResponceListener = new OnResponseListener() {
            @Override
            public void onResponseReceived(ResponseData data, Object tag) {
                if (listener != null) {
                    listener.onResponseReceived(data, tag);
                }
            }

            @Override
            public void onError(VolleyError error, Object tag) {
                if (VolleyResponceUtils.isAuthError(error)) {
                    if (!loginManager.canRestoreLogin()) {
                        if (listener != null) {
                            listener.onError(error, tag);
                        }
                    }
                } else {
                    if (listener != null) {
                        listener.onError(error, tag);
                    }
                }
            }

            @Override
            public void onCancel(Object tag) {
                if (listener != null) {
                    listener.onCancel(tag);
                }
            }
        };

        ResponseData result = performRequestNoLoginRestore(request, tag, loginRestoreResponceListener, true);
        if (VolleyResponceUtils.isAuthError(result.getError()) ||
                (loginManager.domainDependsOnLogin() && VolleyResponceUtils.isNotFoundError(result.getError()))) {
            if (loginManager.canRestoreLogin()) {
                boolean restored = loginManager.restoreLoginData(queue);
                if (restored) {
                    result = performRequestNoLoginRestore(request, tag, new OnResponceAuthListenerDecorator(listener), true);
                } else {
                    listener.onError(result.getError(), tag);
                }
            } else {
                loginManager.onLoginRestoreFailed();
            }
        }
        return result;
    }


    /**
     * @param entity                 Object, specifying request parameters, retrieved data will be merged to this object.
     * @param responseClassSpecifier Class<?> or Type of the object, returned as data field of ResultData object, can be null if you don't need one.
     * @param tag                    will be attached to request and returned in listener callback, can be used in order to cancel request
     * @param synchronous            if true - result will be returned synchronously.
     * @return ResponseData object or null if request was asynchronous.
     */
    public ResponseData getObject(AbstractBaseDrupalEntity entity, Object responseClassSpecifier, Object tag, OnResponseListener listener, boolean synchronous) {
        BaseRequest request = new BaseRequest(RequestMethod.GET, getURLForEntity(entity), this.requestFormat, responseClassSpecifier);
        request.setGetParameters(entity.getItemRequestGetParameters(RequestMethod.GET));
        request.setRequestHeaders(entity.getItemRequestHeaders(RequestMethod.GET));
        return this.performRequest(request, tag, listener, synchronous);
    }

    /**
     * @param entity                 Object, specifying request parameters
     * @param responseClassSpecifier Class<?> or Type of the object, returned as data field of ResultData object, can be null if you don't need one.
     * @param tag                    will be attached to request and returned in listener callback, can be used in order to cancel request
     * @param synchronous            if true - result will be returned synchronously.
     * @return ResponseData object or null if request was asynchronous.
     */
    public ResponseData postObject(AbstractBaseDrupalEntity entity, Object responseClassSpecifier, Object tag, OnResponseListener listener, boolean synchronous) {
        BaseRequest request = new BaseRequest(RequestMethod.POST, getURLForEntity(entity), this.requestFormat, responseClassSpecifier);
        Map<String, String> postParams = entity.getItemRequestPostParameters();
        if (postParams == null || postParams.isEmpty()) {
            request.setObjectToPost(entity.getManagedData());
        } else {
            request.setPostParameters(postParams);
        }
        request.setGetParameters(entity.getItemRequestGetParameters(RequestMethod.POST));
        request.setRequestHeaders(entity.getItemRequestHeaders(RequestMethod.POST));
        return this.performRequest(request, tag, listener, synchronous);
    }

    /**
     * @param entity                 Object, specifying request parameters, must have "createFootPrint" called before.
     * @param responseClassSpecifier Class<?> or Type of the object, returned as data field of ResultData object, can be null if you don't need one.
     * @param tag                    will be attached to request and returned in listener callback, can be used in order to cancel request
     * @param synchronous            if true - result will be returned synchronously.
     * @return ResponseData object or null if request was asynchronous.
     */
    public ResponseData patchObject(AbstractBaseDrupalEntity entity, Object responseClassSpecifier, Object tag, OnResponseListener listener, boolean synchronous) {
        BaseRequest request = new BaseRequest(RequestMethod.PATCH, getURLForEntity(entity), this.requestFormat, responseClassSpecifier);
        request.setGetParameters(entity.getItemRequestGetParameters(RequestMethod.PATCH));
        request.setObjectToPost(entity.getPatchObject());
        request.setRequestHeaders(entity.getItemRequestHeaders(RequestMethod.PATCH));
        return this.performRequest(request, tag, listener, synchronous);
    }

    /**
     * @param entity                 Object, specifying request parameters
     * @param responseClassSpecifier Class<?> or Type of the object, returned as data field of ResultData object, can be null if you don't need one.
     * @param tag                    will be attached to request and returned in listener callback, can be used in order to cancel request
     * @param synchronous            if true - result will be returned synchronously.
     * @return ResponseData object or null if request was asynchronous.
     */
    public ResponseData deleteObject(AbstractBaseDrupalEntity entity, Object responseClassSpecifier, Object tag, OnResponseListener listener,
            boolean synchronous) {
        BaseRequest request = new BaseRequest(RequestMethod.DELETE, getURLForEntity(entity), this.requestFormat, responseClassSpecifier);
        request.setGetParameters(entity.getItemRequestGetParameters(RequestMethod.DELETE));
        request.setRequestHeaders(entity.getItemRequestHeaders(RequestMethod.DELETE));
        return this.performRequest(request, tag, listener, synchronous);
    }

    /**
     * @return request timeout millis
     */
    public int getRequestTimeout() {
        return requestTimeout;
    }

    /**
     * @param requestTimeout request timeout millis
     */
    public void setRequestTimeout(int requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    private String getURLForEntity(AbstractBaseDrupalEntity entity) {
        String path = entity.getPath();
        if (!path.isEmpty() && path.charAt(0) == '/') {
            path = path.substring(1);
        }
        return this.baseURL + path;
    }

    /**
     * This request is always synchronous and has no callback
     */
    public final Object login(final String userName, final String password) {
        return this.loginManager.login(userName, password, queue);
    }

    /**
     * This request is always synchronous
     */
    public final void logout() {
        this.loginManager.logout(queue);
    }

    /**
     * @return true all necessary user id data is fetched and login can be restored automatically
     */
    public boolean isLogged() {
        return this.loginManager.canRestoreLogin();
    }

    public ILoginManager getLoginManager() {
        return loginManager;
    }

    public void setLoginManager(ILoginManager loginManager) {
        this.loginManager = loginManager;
    }

    /**
     * Synchronous login restore attempt
     *
     * @return false if login restore failed.
     */
    public boolean restoreLogin() {
        if (this.loginManager.canRestoreLogin()) {
            return this.loginManager.restoreLoginData(queue);
        }
        return false;
    }

    @Override
    public void onResponseReceived(ResponseData data, BaseRequest request) {
        List<OnResponseListener> listenerList = this.listeners.getListenersForRequest(request);
        this.listeners.removeListenersForRequest(request);
        this.onRequestComplete();
        if (listenerList != null) {
            for(OnResponseListener list:listenerList) {
                list.onResponseReceived(data, request.getTag());
            }
        }
    }

    @Override
    public void onError(VolleyError error, BaseRequest request) {
        List<OnResponseListener> listenerList = this.listeners.getListenersForRequest(request);
        this.listeners.removeListenersForRequest(request);
        this.onRequestComplete();
        if (listenerList != null) {
            for(OnResponseListener list:listenerList) {
                list.onError(error, request.getTag());
            }
        }
    }

    /**
     * @return Charset, used to encode/decode server request post body and response.
     */
    public String getDefaultCharset() {
        return defaultCharset;
    }

    /**
     * @param defaultCharset Charset, used to encode/decode server request post body and response.
     */
    public void setDefaultCharset(String defaultCharset) {
        this.defaultCharset = defaultCharset;
    }

    /**
     * @param tag Cancel all requests, containing given tag. If no tag is specified - all requests are canceled.
     */
    public void cancelByTag(final @NonNull Object tag) {
        this.cancelAllRequestsForListener(null, tag);
    }

    /**
     * Cancel all requests
     */
    public void cancelAll() {
        this.cancelAllRequestsForListener(null, null);
    }

    /**
     * If true - duplicate simultaneous requests will be ignored (all response listeners will be triggered after first unique request instance completes). Similarity is defined based on requests "equals" value
     * @return
     */
    public boolean isAllowDuplicateRequests() {
        return allowDuplicateRequests;
    }

    /**
     *  If true - duplicate simultaneous requests will be ignored (all response listeners will be triggered after first unique request instance completes).  Similarity is defined based on requests "equals" value
     * @param allowDuplicateRequests
     */
    public void setAllowDuplicateRequests(boolean allowDuplicateRequests) {
        this.allowDuplicateRequests = allowDuplicateRequests;
    }

    /**
     * Cancel all requests for given listener with tag
     *
     * @param theListener listener to cancel requests for in case if null passed- all requests for given tag will be canceled
     * @param theTag      to cancel requests for, in case if null passed- all requests for given listener will be canceled
     */
    public void cancelAllRequestsForListener(final @Nullable OnResponseListener theListener, final @Nullable Object theTag) {
        this.queue.cancelAll(new RequestQueue.RequestFilter() {
            @Override
            public boolean apply(Request<?> request) {
                if (theTag == null || theTag.equals(request.getTag())) {
                    List<OnResponseListener> listenerList = listeners.getListenersForRequest(request);

                    if (theListener == null || listenerList.equals(theListener)) {
                        if (listenerList != null) {
                            listeners.removeListenersForRequest(request);
                           for(OnResponseListener list:listenerList) {
                               list.onCancel(request.getTag());
                           }
                            DrupalClient.this.onRequestComplete();
                        }
                        return true;
                    }
                }

                return false;
            }
        });
    }

    // Manage request progress

    /**
     * @return number of requests pending
     */
    public int getActiveRequestsCount() {
        return this.listeners.registeredRequestCount();
    }

    public void setBaseURL(String theBaseURL) {
        if (theBaseURL.charAt(theBaseURL.length() - 1) != '/') {
            theBaseURL += '/';
        }
        ;
        this.baseURL = theBaseURL;
    }

    public String getBaseURL() {
        return baseURL;
    }

    private void onNewRequestStarted() {
        if (this.progressListener != null) {

            int requestCount = this.getActiveRequestsCount();
            this.progressListener.onRequestStarted(this, requestCount);

        }
    }

    private void onRequestComplete() {
        if (this.progressListener != null) {
            int requestCount = this.getActiveRequestsCount();
            this.progressListener.onRequestFinished(this, requestCount);
        }
    }

    private class OnResponceAuthListenerDecorator implements OnResponseListener {

        private OnResponseListener listener;

        OnResponceAuthListenerDecorator(OnResponseListener listener) {
            this.listener = listener;
        }

        @Override
        public void onResponseReceived(ResponseData data, Object tag) {
            if (listener != null) {
                this.listener.onResponseReceived(data, tag);
            }
        }

        @Override
        public void onError(VolleyError error, Object tag) {
            if (VolleyResponceUtils.isAuthError(error)) {
                loginManager.onLoginRestoreFailed();
            }
            if (listener != null) {
                this.listener.onError(error, tag);
            }
        }

        @Override
        public void onCancel(Object tag) {
            if (listener != null) {
                this.listener.onCancel(tag);
            }
        }
    }

    private class RestoreLoginAttemptTask {

        private final BaseRequest request;
        private final OnResponseListener listener;
        private final Object tag;
        private final VolleyError originError;

        RestoreLoginAttemptTask(BaseRequest request, OnResponseListener listener, Object tag, VolleyError originError) {
            this.request = request;
            this.listener = listener;
            this.tag = tag;
            this.originError = originError;
        }

        public void execute() {
            new Thread() {
                @Override
                public void run() {

                    boolean restored = loginManager.restoreLoginData(queue);
                    if (restored) {
                        performRequestNoLoginRestore(request, tag, new OnResponceAuthListenerDecorator(listener), false);
                    } else {
                        listener.onError(originError, tag);
                    }
                }
            }.start();
        }
    }

}