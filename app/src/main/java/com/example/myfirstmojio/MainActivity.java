package com.example.myfirstmojio;

import android.content.Intent;
import android.os.Bundle;
import android.support.v7.app.ActionBarActivity;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.MapFragment;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.model.BitmapDescriptor;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;

import org.json.JSONException;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import io.moj.mobile.android.sdk.MojioClient;
import io.moj.mobile.android.sdk.models.Observers.Observer;
import io.moj.mobile.android.sdk.models.User;
import io.moj.mobile.android.sdk.models.Vehicle;

public class MainActivity extends ActionBarActivity implements OnMapReadyCallback {

    private static final String TAG = MainActivity.class.getSimpleName();

    //===========================================================================
    // Mojio App Setup
    // These values will match the keys given to you for your Mojio application in the
    // Mojio Developer panel.
    //===========================================================================

    private final static String MOJIO_APP_ID = "<YOUR_APP_ID>";
    private final static String REDIRECT_URL = "<YOUR_APP_REDIRECT>://"; // Example "myfirstmojio://"

    //===========================================================================
    // Activity properties
    //===========================================================================
    // Activity request ID to allow us to listen for the OAuth2 response
    private static int OAUTH_REQUEST = 0;
    private BitmapDescriptor carIconDescriptor;

    // The main mojio client object; allows login and data retrieval to occur.
    private MojioClient mMojio;

    private User mCurrentUser;
    private Map<String, Vehicle> mUserVehicles = new HashMap<>();
    private Map<String, Marker> mMapMarkers = new HashMap<>();

    private Button mLoginButton;
    private TextView mUserNameTextView, mUserEmailTextView, mNoVehiclesTextView;
    private ListView mVehicleListView;
    private Switch mSandboxSwitch;
    private ProgressBar mProgressBar;
    private MapFragment mMap;

    //===========================================================================
    // Activity implementation
    //===========================================================================
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Setup mojio client with app keys.
        mMojio = new MojioClient(this, MOJIO_APP_ID, null, REDIRECT_URL);

        mUserNameTextView = (TextView) findViewById(R.id.user_name);
        mUserEmailTextView = (TextView) findViewById(R.id.user_email);
        mNoVehiclesTextView = (TextView) findViewById(R.id.txt_no_vehicles);
        mVehicleListView = (ListView) findViewById(R.id.vehicle_list);
        mMap = (MapFragment) getFragmentManager().findFragmentById(R.id.map_fragment);

        mLoginButton = (Button) findViewById(R.id.oauth2_login_button);
        mLoginButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doOauth2Login();
            }
        });

        mProgressBar = (ProgressBar) findViewById(R.id.progress);

        mSandboxSwitch = (Switch) findViewById(R.id.sandbox);
        mSandboxSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                setSandbox(isChecked);
            }
        });

        // only need to load this once
        carIconDescriptor = BitmapDescriptorFactory.fromResource(R.drawable.ic_map_my_car_location);
    }

    // IMPORTANT: This uses the com.mojio.mojiosdk.networking.OAuthLoginActivity class.
    // For this to work correctly, we must declare it as an Activity in our app's AndroidManifest.xml file.
    private void doOauth2Login() {
        // Launch the OAuth request; this will launch a web view Activity for the user enter their login.
        // When the Activity finishes, we listen for it in the onActivityResult method
        mMojio.launchLoginActivity(this, OAUTH_REQUEST);

    }

    // IMPORTANT: Must be overridden so that we can listen for the OAuth2 result and know if we were
    // logged in successfully. We do not have to bother with storing the auth tokens, the SDK codes that
    // for us.
    @Override
    protected void onActivityResult (int requestCode, int resultCode, Intent data) {
        if (requestCode == OAUTH_REQUEST) {
            // We now have a stored access token
            if (resultCode == RESULT_OK) {
                Toast.makeText(MainActivity.this, R.string.login_success, Toast.LENGTH_LONG).show();
                getCurrentUser(); // Now attempt to get user info
            }
            else {
                Toast.makeText(MainActivity.this, R.string.error_login, Toast.LENGTH_LONG).show();
            }
        }
    }

    // We have our access token stored now with the client, but we now need to grab our user ID.
    private void getCurrentUser() {
        String entityPath = "Users";
        HashMap<String, String> queryParams = new HashMap<>();
        mMojio.get(User[].class, entityPath, queryParams, new GetCurrentUserResponseListener(this));
    }

    // Now that we have the current user, we can use their ID to get data
    private void getUserVehicles() {
        mNoVehiclesTextView.setVisibility(View.INVISIBLE);
        mProgressBar.setVisibility(View.VISIBLE);

        String entityPath = String.format("Users/%s/Vehicles", mCurrentUser._id);
        HashMap<String, String> queryParams = new HashMap<>();
        queryParams.put("sortBy", "Name");
        queryParams.put("desc", "true");
        mMojio.get(Vehicle[].class, entityPath, queryParams, new GetUserVehiclesResponseListener(this));
    }

    private void setSandbox(boolean enabled) {
        mMojio.setSandboxedAccess(enabled, new SetSandboxedResponseListener(MainActivity.this));
    }

    // If we are here then we must already have vehicles!
    // Load the MapFragment as recommended by google, and then display the vehicle locations
    private void updateMap() {
        // Setup map!
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mMap.getMapAsync(MainActivity.this);
            }
        });
    }

    @Override
    public void onMapReady(GoogleMap map) {
        // Map is ready
        LatLng vPos = new LatLng(49.282, -123.1207); // Default to Vancouver

        // Iterate over each vehicle and show it on the map
        for (Vehicle v : mUserVehicles.values()) {
            try {
                vPos = new LatLng(v.LastLocation.Lat, v.LastLocation.Lng);

                Marker marker = mMapMarkers.get(v._id);
                if (marker == null) {
                    marker = map.addMarker(new MarkerOptions()
                            .title(v.VehicleName)
                            .position(vPos)
                            .icon(carIconDescriptor)
                            .anchor(0.5f, 0.5f));
                } else {
                    Log.d(TAG, "Updating marker position to: " + vPos);
                    marker.setPosition(vPos);
                }
                mMapMarkers.put(v._id, marker);
            } catch (Exception e) {
                Toast.makeText(MainActivity.this, "No location for " + v.VehicleName, Toast.LENGTH_SHORT).show();
            }
        }

        // Remove markers for vehicles no longer being returned
        Set<String> removedIds = new HashSet<>();
        for (String key : mMapMarkers.keySet()) {
            if (!mUserVehicles.containsKey(key)) {
                removedIds.add(key);
            }
        }

        for (String key : removedIds) {
            mMapMarkers.get(key).remove();
            mMapMarkers.remove(key);
        }

        map.setMyLocationEnabled(true);

        map.moveCamera(CameraUpdateFactory.newLatLngZoom(vPos, 12));
    }

    //===========================================================================
    // Response Listeners
    // Note: We do this to avoid holding a hard reference to MainActivity as anonymous classes
    // would. This way, if the Activity stops while a request is ongoing, we won't leak the
    // Context (and also avoid errors trying to update UI elements that are no longer shown).
    //===========================================================================
    private static class GetCurrentUserResponseListener implements MojioClient.ResponseListener<User[]> {
        private WeakReference<MainActivity> parentRef;

        public GetCurrentUserResponseListener(MainActivity parent) {
            this.parentRef = new WeakReference<>(parent);
        }

        @Override
        public void onSuccess(User[] result) {
            MainActivity parent = parentRef.get();
            if (parent != null) {
                if (result == null || result.length == 0) {
                    Toast.makeText(parent, R.string.error_get_current_user, Toast.LENGTH_SHORT).show();
                    return;
                }

                // Should have one result
                parent.mCurrentUser = result[0]; // Save user info so we can use ID later

                // Show user data
                parent.mUserNameTextView.setText("Hello " + parent.mCurrentUser.FirstName + " " + parent.mCurrentUser.LastName);
                parent.mUserEmailTextView.setText(parent.mCurrentUser.Email);
                parent.mLoginButton.setVisibility(View.GONE);
                parent.mSandboxSwitch.setVisibility(View.VISIBLE);

                parent.getUserVehicles();
            }
        }

        @Override
        public void onFailure(String error) {
            MainActivity parent = parentRef.get();
            if (parent != null) {
                Toast.makeText(parent, "Problem getting users", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private static class GetUserVehiclesResponseListener implements MojioClient.ResponseListener<Vehicle[]> {
        private WeakReference<MainActivity> parentRef;

        public GetUserVehiclesResponseListener(MainActivity parent) {
            this.parentRef = new WeakReference<>(parent);
        }

        @Override
        public void onSuccess(Vehicle[] result) {
            MainActivity parent = parentRef.get();
            if (parent != null) {
                // save the result and create list data
                ArrayList<String> listData = new ArrayList<>();
                for (Vehicle v : result) {
                    parent.mUserVehicles.put(v._id, v);
                    listData.add(v.VehicleName + (TextUtils.isEmpty(v.LicensePlate) ? "" : "(" + v.LicensePlate + ")"));

                    // register an observer for each vehicle
                    try {
                        JSONObject content = new JSONObject();
                        content.put("Name", "Vehicle Observer " + v._id);
                        content.put("Subject", "Vehicle");
                        content.put("SubjectId", v._id);
                        content.put("Transports", 1);
                        content.put("BroadcastOnlyRecent", "false");
                        parent.mMojio.createObserver(Observer.class, content.toString(), new VehicleObserverResponseListener(parent));
                    } catch (JSONException e) {
                        Log.e(TAG, "Error creating observer for vehicle " + v._id, e);
                    }
                }

                parent.mProgressBar.setVisibility(View.GONE);
                parent.mNoVehiclesTextView.setVisibility(parent.mUserVehicles.size() > 0 ? View.GONE : View.VISIBLE);

                // Show result in list
                ArrayAdapter<String> itemsAdapter = new ArrayAdapter<>(parent, android.R.layout.simple_list_item_1, listData);
                parent.mVehicleListView.setAdapter(itemsAdapter);
                parent.updateMap();
            }
        }

        @Override
        public void onFailure(String error) {
            MainActivity parent = parentRef.get();
            if (parent != null) {
                Toast.makeText(parent, "Problem getting vehicles", Toast.LENGTH_LONG).show();
            }
        }
    }

    private static class SetSandboxedResponseListener implements MojioClient.ResponseListener<Boolean> {
        private WeakReference<MainActivity> parentRef;

        public SetSandboxedResponseListener(MainActivity parent) {
            this.parentRef = new WeakReference<>(parent);
        }

        @Override
        public void onSuccess(Boolean result) {
            MainActivity parent = parentRef.get();
            if (parent != null) {
                parent.getUserVehicles();
            }

        }

        @Override
        public void onFailure(String s) {
            MainActivity parent = parentRef.get();
            if (parent != null) {
                Toast.makeText(parent, R.string.error_set_sandbox, Toast.LENGTH_SHORT).show();
            }
        }
    }

    private static class VehicleObserverResponseListener implements MojioClient.ResponseListener<Observer> {
        private WeakReference<MainActivity> parentRef;

        public VehicleObserverResponseListener(MainActivity parent) {
            this.parentRef = new WeakReference<>(parent);
        }

        @Override
        public void onSuccess(Observer observer) {
            MainActivity parent = parentRef.get();
            if (parent != null) {
                Log.d(TAG, "Subscribing observer " + observer._id + "...");
                parent.mMojio.subscribeToObserver(Vehicle.class, observer, new VehicleObserverUpdateListener(parent));
            }
        }

        @Override
        public void onFailure(String s) {
            Log.e(TAG, "Error creating vehicle observer: " + s);
        }
    }

    private static class VehicleObserverUpdateListener implements MojioClient.ResponseListener<Vehicle> {
        private WeakReference<MainActivity> parentRef;

        public VehicleObserverUpdateListener(MainActivity parent) {
            this.parentRef = new WeakReference<>(parent);
        }

        @Override
        public void onSuccess(Vehicle vehicle) {
            MainActivity parent = parentRef.get();
            if (parent != null) {
                Log.d(TAG, "Received vehicle update");
                parent.mUserVehicles.put(vehicle._id, vehicle);
                parent.updateMap();
            }
        }

        @Override
        public void onFailure(String s) {
            Log.e(TAG, "Error on Vehicle Observer update: " + s);
        }
    }

}
