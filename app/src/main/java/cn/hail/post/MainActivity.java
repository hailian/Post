package cn.hail.post;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.snackbar.Snackbar;
import com.microsoft.connecteddevices.AsyncOperation;
import com.microsoft.connecteddevices.CancellationToken;
import com.microsoft.connecteddevices.ConnectedDevicesAccessTokenInvalidatedEventArgs;
import com.microsoft.connecteddevices.ConnectedDevicesAccessTokenRequestedEventArgs;
import com.microsoft.connecteddevices.ConnectedDevicesAccount;
import com.microsoft.connecteddevices.ConnectedDevicesAccountManager;
import com.microsoft.connecteddevices.ConnectedDevicesAddAccountResult;
import com.microsoft.connecteddevices.ConnectedDevicesNotificationRegistrationManager;
import com.microsoft.connecteddevices.ConnectedDevicesNotificationRegistrationStateChangedEventArgs;
import com.microsoft.connecteddevices.ConnectedDevicesPlatform;
import com.microsoft.connecteddevices.EventListener;
import com.microsoft.connecteddevices.remotesystems.RemoteSystem;
import com.microsoft.connecteddevices.remotesystems.RemoteSystemAddedEventArgs;
import com.microsoft.connecteddevices.remotesystems.RemoteSystemAuthorizationKind;
import com.microsoft.connecteddevices.remotesystems.RemoteSystemAuthorizationKindFilter;
import com.microsoft.connecteddevices.remotesystems.RemoteSystemDiscoveryType;
import com.microsoft.connecteddevices.remotesystems.RemoteSystemDiscoveryTypeFilter;
import com.microsoft.connecteddevices.remotesystems.RemoteSystemFilter;
import com.microsoft.connecteddevices.remotesystems.RemoteSystemRemovedEventArgs;
import com.microsoft.connecteddevices.remotesystems.RemoteSystemStatusType;
import com.microsoft.connecteddevices.remotesystems.RemoteSystemStatusTypeFilter;
import com.microsoft.connecteddevices.remotesystems.RemoteSystemUpdatedEventArgs;
import com.microsoft.connecteddevices.remotesystems.RemoteSystemWatcher;
import com.microsoft.connecteddevices.remotesystems.RemoteSystemWatcherErrorOccurredEventArgs;
import com.microsoft.connecteddevices.remotesystems.commanding.RemoteSystemConnectionRequest;
import com.microsoft.connecteddevices.remotesystems.commanding.nearshare.NearShareFileProvider;
import com.microsoft.connecteddevices.remotesystems.commanding.nearshare.NearShareHelper;
import com.microsoft.connecteddevices.remotesystems.commanding.nearshare.NearShareSender;
import com.microsoft.connecteddevices.remotesystems.commanding.nearshare.NearShareStatus;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import android.text.format.Formatter;
import android.util.ArrayMap;
import android.view.LayoutInflater;
import android.view.View;

import android.view.Menu;
import android.view.MenuItem;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;

import cn.hail.post.base.DiscoverListener;
import cn.hail.post.base.Peer;
import cn.hail.post.base.SendingSession;
import cn.hail.post.nearbysharing.NearShareManager;
import cn.hail.post.nearbysharing.NearSharePeer;

import static android.content.Intent.ACTION_SEND;
import static android.content.Intent.ACTION_SEND_MULTIPLE;

import static android.Manifest.permission.WRITE_EXTERNAL_STORAGE;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;

public class MainActivity extends AppCompatActivity implements DiscoverListener {

    // region Member Variables
    private final static Logger LOG = Logger.getLogger(MainActivity.class.getSimpleName());

    private final WifiStateMonitor mWifiStateMonitor = new WifiStateMonitor() {
        @Override
        public void onReceive(Context context, Intent intent) {
            setupIfNeeded();
        }
    };
    private final BluetoothStateMonitor mBluetoothStateMonitor = new BluetoothStateMonitor() {
        @Override
        public void onReceive(Context context, Intent intent) {
            setupIfNeeded();
        }
    };

    private final ArrayMap<String, Peer> mPeers = new ArrayMap<>();
    private final Map<String, PeerState> mPeerStates = new HashMap<>();

    private PeersAdapter mAdapter;
    private NearShareManager mNearShareManager;

    private String mPeerPicked = null;

    //private PartialWakeLock mWakeLock;

    private boolean mIsInSetup = false;

    private boolean mIsDiscovering = false;
    private boolean mShouldKeepDiscovering = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        mAdapter = new PeersAdapter(this);

        final RecyclerView peersView = findViewById(R.id.peers);
        peersView.setAdapter(mAdapter);

        mNearShareManager = new NearShareManager(this);
    }

    @Override
    public void onPeerFound(Peer peer) {
        LOG.info("Found: " + peer.id + " (" + peer.name + ")");
        mPeers.put(peer.id, peer);
        mPeerStates.put(peer.id, new PeerState());
        mAdapter.notifyDataSetChanged();
    }

    @Override
    public void onPeerDisappeared(Peer peer) {
        LOG.info("Disappeared: " + peer.id + " (" + peer.name + ")");
        mPeers.remove(peer.id);
        mPeerStates.remove(peer.id);
        mAdapter.notifyDataSetChanged();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mNearShareManager.destroy();
    }

    @Override
    protected void onResume() {
        super.onResume();

        mWifiStateMonitor.register(this);
        mBluetoothStateMonitor.register(this);

        if (setupIfNeeded()) {
            return;
        }

        if (!mIsDiscovering) {
            mNearShareManager.startDiscover(this);
            mIsDiscovering = true;
        }
    }

    @Override
    protected void onPause() {
        super.onPause();

        if (mIsDiscovering && !mShouldKeepDiscovering) {
            mNearShareManager.stopDiscover();
            mIsDiscovering = false;
        }

        mWifiStateMonitor.unregister(this);
        mBluetoothStateMonitor.unregister(this);
    }

    private boolean setupIfNeeded() {
        if (mIsInSetup) {
            return true;
        }
        final boolean granted = checkSelfPermission(WRITE_EXTERNAL_STORAGE) == PERMISSION_GRANTED;
        if (!granted ) {
            mIsInSetup = true;
            //todo
            //startActivityForResult(new Intent(this, SetupActivity.class), REQUEST_SETUP);
            return true;
        } else {
            return false;
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        // noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private class PeersAdapter extends RecyclerView.Adapter<PeersAdapter.ViewHolder> {

        private final LayoutInflater mInflater;

        PeersAdapter(Context context) {
            mInflater = LayoutInflater.from(context);
            setHasStableIds(true);
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ViewHolder(mInflater.inflate(R.layout.item_peer_main, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            LOG.info(" --> onBindViewHolder");
            final String id = mPeers.keyAt(position);
            final Peer peer = mPeers.valueAt(position);
            final PeerState state = mPeerStates.get(id);

            assert state != null;

            LOG.info(" --> id = " + id);
            LOG.info(" --> peer : id = " + peer.id + ", name = " + peer.name);
            LOG.info(" --> state : status = " + state.status);

            holder.nameView.setText(peer.name);
            if (state.status != 0) {
                LOG.info(" --> ( state.status != 0 ) ");
                holder.itemView.setSelected(true);
                holder.statusView.setVisibility(View.VISIBLE);
                if (state.status == R.string.status_sending && state.bytesTotal != -1) {
                    holder.statusView.setText(getString(R.string.status_sending_progress,
                            Formatter.formatFileSize(MainActivity.this, state.bytesSent),
                            Formatter.formatFileSize(MainActivity.this, state.bytesTotal)));
                } else {
                    holder.statusView.setText(state.status);
                    LOG.info(" --> ( else 0.1 ) ");
                }
            } else {
                LOG.info(" --> ( else 1 ) ");
                holder.itemView.setSelected(false);
                holder.statusView.setVisibility(View.GONE);
            }
            if (state.status != 0 && state.status != R.string.status_rejected) {

                LOG.info(" --> ( state.status != 0 && state.status != R.string.status_rejected ) ");
                holder.itemView.setEnabled(false);
                holder.progressBar.setVisibility(View.VISIBLE);
                holder.cancelButton.setVisibility(View.VISIBLE);
                if (state.bytesTotal == -1 || state.status != R.string.status_sending) {
                    holder.progressBar.setIndeterminate(true);
                } else {
                    holder.progressBar.setIndeterminate(false);
                    holder.progressBar.setMax((int) state.bytesTotal);
                    holder.progressBar.setProgress((int) state.bytesSent, true);
                    LOG.info(" --> ( else 1.1 ) ");
                }
            } else {

                LOG.info(" --> ( else 2 ) ");
                holder.itemView.setEnabled(true);
                holder.progressBar.setVisibility(View.GONE);
                holder.cancelButton.setVisibility(View.GONE);
            }
//            if (peer instanceof AirDropPeer) {
//                final boolean isMokee = ((AirDropPeer) peer).getMokeeApiVersion() > 0;
//                if (isMokee) {
//                    holder.iconView.setImageResource(R.drawable.ic_mokee_24dp);
//                } else {
//                    holder.iconView.setImageResource(R.drawable.ic_apple_24dp);
//                }
//            } else
            if (peer instanceof NearSharePeer) {
                holder.iconView.setImageResource(R.drawable.ic_windows_24dp);
                LOG.info(" --> ( else 2 ) ");
            } else {
                //holder.iconView.setImageDrawable(null);
                holder.iconView.setImageResource(R.drawable.ic_mokee_24dp);
            }
            //todo
            //holder.itemView.setOnClickListener(v -> handleItemClick(peer));
            //holder.cancelButton.setOnClickListener(v -> handleItemCancelClick(peer, state));
        }

        @Override
        public long getItemId(int position) {
            return mPeers.keyAt(position).hashCode();
        }

        @Override
        public int getItemCount() {
            return mPeers.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {

            ImageView iconView;
            TextView nameView;
            TextView statusView;
            ProgressBar progressBar;
            View cancelButton;

            ViewHolder(@NonNull View itemView) {
                super(itemView);
                iconView = itemView.findViewById(R.id.icon);
                nameView = itemView.findViewById(R.id.name);
                statusView = itemView.findViewById(R.id.status);
                progressBar = itemView.findViewById(R.id.progress);
                cancelButton = itemView.findViewById(R.id.cancel);
            }

        }

    }

    private class PeerState {

        @StringRes
        int status = 0;

        long bytesTotal = -1;
        long bytesSent = 0;

        SendingSession sending = null;

    }

}