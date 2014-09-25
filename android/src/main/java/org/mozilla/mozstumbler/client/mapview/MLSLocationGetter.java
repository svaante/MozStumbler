/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.mozstumbler.client.mapview;

import android.location.Location;
import android.os.AsyncTask;
import android.util.Log;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.concurrent.atomic.AtomicInteger;

import org.json.JSONException;
import org.json.JSONObject;
import org.mozilla.mozstumbler.client.ClientPrefs;
import org.mozilla.mozstumbler.core.adapters.LocationAdapter;
import org.mozilla.mozstumbler.service.utils.AbstractCommunicator;
import org.mozilla.mozstumbler.service.AppGlobals;
import org.mozilla.mozstumbler.service.utils.Zipper;

/*

This class provides MLS locations by calling HTTP methods against the MLS.

 */
public class MLSLocationGetter extends AsyncTask<String, Void, JSONObject>  {
    private static final String LOG_TAG = AppGlobals.LOG_PREFIX + MLSLocationGetter.class.getSimpleName();
    private static final String RESPONSE_OK_TEXT = "ok";
    private final IchnaeaCommunicator mCommunicator;
    private final MLSLocationGetterCallback mCallback;
    private JSONObject mQueryMLS;
    private final int MAX_REQUESTS = 10;
    private static AtomicInteger sRequestCounter = new AtomicInteger(0);

    public interface MLSLocationGetterCallback {
        void setMLSResponseLocation(Location loc);
    }

    public MLSLocationGetter(MLSLocationGetterCallback callback, JSONObject mlsQueryObj) {
        mCallback = callback;
        try {
            mQueryMLS = new JSONObject(mlsQueryObj.toString());
        } catch (JSONException ex) {}

        mCommunicator = new IchnaeaCommunicator();
    }

    @Override
    public JSONObject doInBackground(String... params) {
        assert(mQueryMLS != null);

        int requests = sRequestCounter.incrementAndGet();
        if (requests > MAX_REQUESTS) {
            return null;
        }
        byte[] bytes = mQueryMLS.toString().getBytes();

        try {
            int bytesSent = mCommunicator.send(bytes, Zipper.ZippedState.eNotZipped);
        }
        catch (IOException ex) {
            mCommunicator.close();
            return null;
        }

        JSONObject response = mCommunicator.getResponse();
        String status = "";
        try {
            status = response.getString("status");
        } catch (JSONException ex) {}
        mCommunicator.close();

        if (!status.equals(RESPONSE_OK_TEXT)) {
            return null;
        }

        return response;
    }

    @Override
    protected void onPostExecute(JSONObject result) {
        sRequestCounter.decrementAndGet();
        if (result == null) {
            return;
        }
        Location location = LocationAdapter.fromJSON(result);
        mCallback.setMLSResponseLocation(location);
    }

    private class IchnaeaCommunicator extends AbstractCommunicator {

        private static final String SEARCH_URL = "https://location.services.mozilla.com/v1/search";

        public IchnaeaCommunicator() {
            super(ClientPrefs.getInstance().getUserAgent());
        }

        // TODO: This is just weird
        // getUrlString() is invoked by AbstractCommunicator::openConnectionAndSetHeaders()
        @Override
        public String getUrlString() {
            return SEARCH_URL;
        }

        private JSONObject initResponse() throws IOException, JSONException {
            InputStream in = new BufferedInputStream(super.getInputStream());
            BufferedReader r = new BufferedReader(new InputStreamReader(in));
            String line;
            StringBuilder total = new StringBuilder(in.available());
            while ((line = r.readLine()) != null) {
                total.append(line);
            }
            r.close();
            in.close();
            return new JSONObject(total.toString());
        }

        // TODO: isAlreadyZipped is completely bonkers. Delete it.
        // we know what the state of the data is, it's uncompressed.
        // Compression just happens on the wire, not in our runtime.
        public int send(byte[] data, Zipper.ZippedState isAlreadyZipped) throws IOException {
            openConnectionAndSetHeaders();
            try {
                if (isAlreadyZipped != Zipper.ZippedState.eAlreadyZipped) {
                    data = zipData(data);
                }
                mHttpURLConnection.setRequestProperty("Content-Encoding","gzip");
            } catch (IOException e) {
                Log.e(LOG_TAG, "Couldn't compress and send data, falling back to plain-text: ", e);
            }

            sendData(data);
            return data.length;
        }

        @Override
        public NetworkSendResult cleanSend(byte[] data) {
            NetworkSendResult result = new NetworkSendResult();
            try {
                result.bytesSent = send(data, Zipper.ZippedState.eNotZipped);
                result.errorCode = 0;
            } catch (IOException e) {
                // do nothing
            }
            return result;
        }

        @Override
        public String getNickname() {
            return null;
        }

        @Override
        public String getEmail() {
            return null;
        }

        public JSONObject getResponse() {
            try {
                JSONObject jsonObject = initResponse();
                return jsonObject;
            } catch (IOException e) {
                Log.e(LOG_TAG, "Couldn't process the response: ", e);
                return null;
            } catch (JSONException e) {
                Log.e(LOG_TAG, "JSON got confused: ", e);
                return null;
            }
        }

        @Override
        public void close() {
            super.close();
        }
    }
}
