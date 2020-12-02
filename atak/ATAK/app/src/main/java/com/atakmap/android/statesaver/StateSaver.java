
package com.atakmap.android.statesaver;

import com.atakmap.android.database.DatabaseFactory;
import com.atakmap.android.database.DatabaseInformation;
import com.atakmap.android.database.DatabaseProvider;
import com.atakmap.android.database.ProviderChangeRequestedListener;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import com.atakmap.android.ipc.AtakBroadcast.DocumentedIntentFilter;
import android.database.sqlite.SQLiteException;
import android.net.Uri;

import com.atakmap.android.data.DataMgmtReceiver;
import com.atakmap.android.maps.AbstractMapComponent;
import com.atakmap.android.maps.DefaultMapGroup;
import com.atakmap.android.maps.MapEvent;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.overlay.DefaultMapGroupOverlay;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.cot.event.CotPoint;
import com.atakmap.coremap.filesystem.FileSystemUtils;
import com.atakmap.coremap.io.FileIOProvider;
import com.atakmap.coremap.io.FileIOProviderFactory;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.time.CoordinatedTime;
import com.atakmap.database.CursorIface;
import com.atakmap.database.DatabaseIface;
import com.atakmap.database.Databases;
import com.atakmap.database.StatementIface;
import com.atakmap.map.layer.feature.datastore.FeatureSpatialDatabase;

import java.io.File;
import java.util.HashMap;
import java.util.Set;
import java.util.Map;

public class StateSaver extends AbstractMapComponent
        implements ProviderChangeRequestedListener {

    public static final String TAG = "StateSaver";

    public final static String TABLE_COTEVENTS = "cotevents";
    public final static String COLUMN_EVENT = "event";
    public final static String COLUMN_JSON = "json_serialized";

    public final static String COLUMN_LAST_UPDATE = "lastUpdateTime";
    public final static String COLUMN_VISIBLE = "visible";
    public final static String COLUMN_UID = "uid";
    public final static String COLUMN_TYPE = "type";
    public final static String COLUMN_ID = "_id";
    final static String COLUMN_QUERY_ORDER = "queryOrder";
    public final static String COLUMN_POINT_GEOM = "point_geom";

    /** the current version of the StateSaver database */
    private static final int VERSION = 5;

    public final static String ADD_CLASSIFICATION_ACTION = "com.atakmap.android.statesaver.ADD_CLASSIFICATION";

    private final static File STATE_SAVER_DB_FILE2 = FileSystemUtils
            .getItem("Databases/statesaver2.sqlite");

    final static Object dbWriteLock = new Object();
    private final static Map<String, Integer> QUERY_ORDER = new HashMap<>();
    static {
        synchronized (dbWriteLock) {
            QUERY_ORDER.put("b-m-r", 2);
            QUERY_ORDER.put("b-m-p-j-alt", 1);
            QUERY_ORDER.put("b-m-p-j", 1);
        }
    }

    /**
     * The StateSaver database. ALL versions of the database must contain a table named
     * <code>cotevents</code> and must contain a column named <code>event</code>, which is the raw
     * XML of the persisted CoT message.
     */
    private DatabaseIface stateSaverDatabase;

    private MapView view;

    private Thread publishThread = null;

    private static StateSaver _instance = null;

    private final Object lock = new Object();

    @Override
    public void onCreate(final Context context, final Intent intent,
            final MapView view) {

        this.view = view;
        synchronized (dbWriteLock) {
            checkStateSaverDatabaseNoSync();
        }

        view.getMapOverlayManager().addOtherOverlay(
                new DefaultMapGroupOverlay(view, new DefaultMapGroup(
                        "Saved Entries")));

        listener = new StateSaverListener(stateSaverDatabase, view);
        publisher = new StateSaverPublisher(stateSaverDatabase, view);

        this.registerReceiver(
                context,
                new ComponentsCreatedReceiver(),
                new DocumentedIntentFilter(
                        "com.atakmap.app.COMPONENTS_CREATED"));

        view.getMapEventDispatcher().addMapEventListener(MapEvent.ITEM_REMOVED,
                listener);
        view.getMapEventDispatcher().addMapEventListener(MapEvent.ITEM_PERSIST,
                listener);

        // initialize a way to dynamically specify classifications
        DocumentedIntentFilter filter = new DocumentedIntentFilter();
        filter.addAction(ADD_CLASSIFICATION_ACTION);
        this.registerReceiver(context, classificationBroadcastReceiver, filter);

        filter = new DocumentedIntentFilter();
        filter.addAction(DataMgmtReceiver.ZEROIZE_CONFIRMED_ACTION);
        this.registerReceiver(context, dataMgmtReceiver, filter);

        _instance = this;
    }

    public static StateSaver getInstance() {
        return _instance;
    }

    @Override
    protected void onDestroyImpl(Context context, MapView view) {
        if (listener != null)
            listener.dispose();

        synchronized (dbWriteLock) {
            if (stateSaverDatabase != null) {
                try {
                    stateSaverDatabase.execute("VACUUM", null);
                } catch (Exception e) {
                    Log.d(TAG, "error", e);
                }
                stateSaverDatabase.close();
                stateSaverDatabase = null;
            }
        }
    }

    /**
     * Listens for new CoT types that components wish to be saved / reloaded.
     */
    private final BroadcastReceiver classificationBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String cotType = intent.getStringExtra("cotType");
            int queryOrder = intent.getIntExtra("queryOrder", 0);

            synchronized (dbWriteLock) {
                // update 'queryOrder'
                QUERY_ORDER.put(cotType, queryOrder);
                if (stateSaverDatabase != null) {
                    StatementIface updateStmt = null;
                    try {
                        updateStmt = stateSaverDatabase
                                .compileStatement("UPDATE "
                                        + TABLE_COTEVENTS + " SET "
                                        + COLUMN_QUERY_ORDER
                                        + " = ? WHERE type = ?");

                        updateStmt.bind(1, queryOrder);
                        updateStmt.bind(2, cotType);

                        updateStmt.execute();
                    } finally {
                        if (updateStmt != null)
                            updateStmt.close();
                    }
                }
            }
        }
    };

    private final BroadcastReceiver dataMgmtReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG, "Clearing table: " + TABLE_COTEVENTS);
            synchronized (StateSaver.dbWriteLock) {
                if (stateSaverDatabase != null)
                    stateSaverDatabase.close();
                stateSaverDatabase = null;
                FileIOProviderFactory.delete(STATE_SAVER_DB_FILE2, FileIOProvider.SECURE_DELETE);
            }
        }
    };

    private StateSaverListener listener;
    private StateSaverPublisher publisher;

    final static File LEGACY_BASE_DIR = FileSystemUtils
            .getItem("Databases/BareBack/CoT");

    public static int getQueryOrder(String type) {
        synchronized (dbWriteLock) {
            return getQueryOrderNoSync(type);
        }
    }

    private static int getQueryOrderNoSync(String type) {
        if (type != null) {
            for (Map.Entry<String, Integer> entry : QUERY_ORDER.entrySet()) {
                if (type.startsWith(entry.getKey()))
                    return entry.getValue();
            }
        }
        return 0;
    }

    @Override
    public void onProviderChangeRequested(DatabaseProvider provider,
            int change) {
        Log.d(TAG, "provider change requested" + provider + " " + change);
        // TODO/VERIFY:
        //      - remove all map items loaded from the statesaver without removing them from the statesaver
        //      - stop statesaver restore if in progress
        //      - remove the statesaver listener (no new items are persisted
        //      - iterate through uid's and call remove on the item.
        //      - set up new statesaver database
        //      - start statesaver restoration

        DatabaseIface oldStateSaverDatabase = stateSaverDatabase;

        DatabaseInformation dbi = new DatabaseInformation(
                Uri.fromFile(STATE_SAVER_DB_FILE2), this);

        DatabaseIface newStateSaverDatabase = DatabaseFactory.create(dbi);
        upgradeDatabase(newStateSaverDatabase);

        // new database is ready, now we need to cut over.

        // stop listening for events and dispose the listener
        view.getMapEventDispatcher().removeMapEventListener(
                MapEvent.ITEM_REMOVED,
                listener);
        view.getMapEventDispatcher().removeMapEventListener(
                MapEvent.ITEM_PERSIST,
                listener);
        listener.dispose();

        synchronized (lock) {
            if (publishThread != null) {
                // dispose the publisher and do not continue publishing, block on 
                // the thread per the contract with the callback.   This means that the 
                // ComponentsCreatedReceiver has already been called and the 
                // publishThread was already created.   
                publisher.cancel();

                while (publishThread.isAlive()) {
                    Log.d(TAG, "waiting for publishing thread to die...");
                    try {
                        Thread.sleep(1000);

                    } catch (Exception ignored) {
                    }

                }

                // determine what database elements were placed on the screen and delete them from the mapView.
                CursorIface result = stateSaverDatabase.query("SELECT " +
                        StateSaver.COLUMN_UID + " FROM "
                        + StateSaver.TABLE_COTEVENTS, null);
                while (result.moveToNext()) {
                    final String uid = result.getString(0);
                    Log.d(TAG, "removing: " + uid);
                    final MapItem mi = view.getMapItem(uid);
                    if (mi != null)
                        mi.removeFromGroup();
                }

            }

            // cut over to the new statesaver
            stateSaverDatabase = newStateSaverDatabase;

            // create the new listener
            listener = new StateSaverListener(stateSaverDatabase, view);

            view.getMapEventDispatcher().addMapEventListener(
                    MapEvent.ITEM_REMOVED,
                    listener);
            view.getMapEventDispatcher().addMapEventListener(
                    MapEvent.ITEM_PERSIST,
                    listener);

            publisher = new StateSaverPublisher(stateSaverDatabase, view);
            publisher.resetStateSaverLoaded();

            if (publishThread != null) {
                // begin publishing the elements from the new statesaver, since 
                // ComponentsCreatedReceiver has already been called and the 
                // publisher was already in progress

                publishThread = new Thread(publisher, "InitialCotPublisher");
                publishThread.start();
            }

            // close out of the old statsaver
            try {
                if (oldStateSaverDatabase != null)
                    oldStateSaverDatabase.close();
            } catch (Exception ignore) {
            }
        }

    }

    private void checkStateSaverDatabaseNoSync() {
        if (stateSaverDatabase == null) {

            DatabaseInformation dbi = new DatabaseInformation(
                    Uri.fromFile(STATE_SAVER_DB_FILE2), this);

            stateSaverDatabase = DatabaseFactory.create(dbi);
            if (stateSaverDatabase != null) {
                upgradeDatabase(stateSaverDatabase);
            } else {
                try {
                    final File f = STATE_SAVER_DB_FILE2;
                    if (!FileIOProviderFactory.renameTo(f, new File(STATE_SAVER_DB_FILE2 + ".corrupt."
                            + new CoordinatedTime().getMilliseconds()))) {
                        Log.d(TAG, "could not move corrup db out of the way");
                    } else {
                        Log.d(TAG,
                                "default statesaver database corrupted, move out of the way: "
                                        + f);
                    }
                } catch (Exception ignored) {
                }
                stateSaverDatabase = DatabaseFactory.create(dbi);
                upgradeDatabase(stateSaverDatabase);
            }

        }
    }

    private static void upgradeDatabase(DatabaseIface db) {

        if (db.getVersion() != VERSION) {
            Log.d(TAG, "Upgrading from v" + db.getVersion()
                    + " to v" + VERSION);
            // drop the transition table
            db.execute("DROP TABLE IF EXISTS event_xfer",
                    null);
            // prepare the transition table
            prepareTransitionTable(db);
            // fill the transition table
            if (Databases.getTableNames(db).contains(
                    TABLE_COTEVENTS)) {
                fillTransitionTable(db);

                Set<String> columnNames = Databases.getColumnNames(
                        db,
                        TABLE_COTEVENTS);

                if (columnNames != null
                        && columnNames.contains(COLUMN_POINT_GEOM)) {
                    try {
                        db.execute(
                                "SELECT DiscardGeometryColumn(\'"
                                        + TABLE_COTEVENTS + "\', \'"
                                        + COLUMN_POINT_GEOM + "\')",
                                null);
                    } catch (SQLiteException ignored) {
                    }
                }
                // drop the old table
                db.execute("DROP TABLE " + TABLE_COTEVENTS,
                        null);
            }

            // build the version1 table
            prepareCoTEventsTable(db);

            final int major = FeatureSpatialDatabase
                    .getSpatialiteMajorVersion(db);
            final int minor = FeatureSpatialDatabase
                    .getSpatialiteMinorVersion(db);

            final String initSpatialMetadataSql;
            if (major > 4 || (major == 4 && minor >= 1))
                initSpatialMetadataSql = "SELECT InitSpatialMetadata(1)";
            else
                initSpatialMetadataSql = "SELECT InitSpatialMetadata()";

            db.execute(initSpatialMetadataSql, null);

            // XXX - 'xyz' for 3D?
            db.execute("SELECT AddGeometryColumn(\'"
                    + TABLE_COTEVENTS
                    + "\', \'" + COLUMN_POINT_GEOM
                    + "\', 4326, \'POINT\', \'XY\')", null);

            // rebuild the table from the transition data
            consumeTransitionTable(db);

            // drop the transition data table
            db.execute("DROP TABLE event_xfer", null);

            db.setVersion(VERSION);
        }
    }

    private static void prepareCoTEventsTable(DatabaseIface db) {
        db.execute("CREATE TABLE " + TABLE_COTEVENTS +
                " (" + COLUMN_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                + COLUMN_UID + " TEXT, "
                + COLUMN_TYPE + " TEXT, "
                + COLUMN_EVENT + " TEXT, "
                + COLUMN_JSON + " TEXT, "
                + COLUMN_VISIBLE + " INTEGER, "
                + COLUMN_LAST_UPDATE + " INTEGER, "
                + COLUMN_QUERY_ORDER + " INTEGER)", null);
    }

    /**
     * Creates a temporary table to hold the XML for all persisted CoT events.
     */
    private static void prepareTransitionTable(DatabaseIface db) {
        db.execute("CREATE TABLE event_xfer (event TEXT)", null);
    }

    /**
     * Fills the transition table with the XML for all persisted CoT events.
     */
    private static void fillTransitionTable(DatabaseIface db) {
        db.execute("INSERT INTO event_xfer SELECT "
                + COLUMN_EVENT + " FROM "
                + TABLE_COTEVENTS, null);
    }

    /**
     * Populates the database from the transition table.
     */
    private static void consumeTransitionTable(DatabaseIface db) {
        CursorIface result = null;
        StatementIface insertStmt = null;
        db.beginTransaction();
        try {
            insertStmt = db
                    .compileStatement("INSERT INTO cotevents "
                            + "("
                            + COLUMN_UID
                            + ", "
                            + COLUMN_TYPE
                            + ", "
                            + COLUMN_EVENT
                            + ", "
                            + COLUMN_JSON
                            + ", "
                            + COLUMN_VISIBLE
                            + ", "
                            + COLUMN_LAST_UPDATE
                            + ", "
                            + COLUMN_QUERY_ORDER
                            + ", "
                            + COLUMN_POINT_GEOM
                            + ") "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, MakePoint(?, ?, 4326))");

            result = db.query("SELECT event FROM event_xfer",
                    null);
            while (result.moveToNext()) {
                CotEvent event = CotEvent.parse(result.getString(0));
                if (event == null)
                    continue;
                try {
                    insertStmt.bind(1, event.getUID());
                    insertStmt.bind(2, event.getType());
                    insertStmt.bind(3, result.getString(0));
                    insertStmt.bind(4, "");
                    insertStmt.bind(5, 1);
                    insertStmt.bind(6, event.getTime().getMilliseconds());
                    insertStmt.bind(7, getQueryOrderNoSync(event.getType()));

                    CotPoint pt = event.getCotPoint();
                    insertStmt.bind(8, pt.getLon());
                    insertStmt.bind(9, pt.getLat());

                    insertStmt.execute();
                } finally {
                    insertStmt.clearBindings();
                }
            }

            db.setTransactionSuccessful();
        } finally {
            if (insertStmt != null)
                insertStmt.close();

            if (result != null)
                result.close();
            db.endTransaction();
        }
    }

    public DatabaseIface getStateSaverDatabase() {
        synchronized (dbWriteLock) {
            checkStateSaverDatabaseNoSync();
            return stateSaverDatabase;
        }
    }

    public static Object getStateSaverDatabaseLock() {
        return dbWriteLock;
    }

    private class ComponentsCreatedReceiver extends BroadcastReceiver {

        private boolean publishStarted = false;

        @Override
        public synchronized void onReceive(Context context, Intent intent) {

            synchronized (lock) {
                if (!publishStarted
                        && FileSystemUtils.isEquals(intent.getAction(),
                                "com.atakmap.app.COMPONENTS_CREATED")) {
                    publishStarted = true;
                    publishThread = new Thread(publisher,
                            "InitialCotPublisher");
                    publishThread.start();
                }
            }
        }
    }

}
