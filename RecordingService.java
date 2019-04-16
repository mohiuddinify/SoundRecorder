package com.browsio.soundrecorder.services;

import android.app.Service;
import android.content.Intent;
import android.media.MediaRecorder;
import android.os.Binder;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

import com.browsio.soundrecorder.DBHelper;
import com.browsio.soundrecorder.RecorderState;
import com.browsio.soundrecorder.fragments.RecordFragment;
import com.browsio.soundrecorder.util.Command;
import com.browsio.soundrecorder.util.EventBroadcaster;
import com.browsio.soundrecorder.util.MyIntentBuilder;
import com.browsio.soundrecorder.util.MySharedPreferences;
import com.browsio.soundrecorder.util.NotificationCompatPie;
import com.browsio.soundrecorder.util.Paths;
import com.coremedia.iso.boxes.Container;
import com.googlecode.mp4parser.authoring.Movie;
import com.googlecode.mp4parser.authoring.Track;
import com.googlecode.mp4parser.authoring.builder.DefaultMp4Builder;
import com.googlecode.mp4parser.authoring.container.mp4.MovieCreator;
import com.googlecode.mp4parser.authoring.tracks.AppendTrack;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

import androidx.annotation.Nullable;
import by.browsio.soundrecorder.R;

/**
 * Created by Daniel on 12/28/2014.
 */
public class RecordingService extends Service {

    private static final String LOG_TAG = "RecordingService";

    private String mFileName = null;
    private String mFilePath = null;
    
    private MediaRecorder mRecorder = null;

    private DBHelper mDatabase;

    private long mStartingTimeMillis = 0;
    private long mElapsedMillis = 0;

    private volatile RecorderState state = RecorderState.STOPPED;
    private int tempFileCount = 0;

    private ArrayList<String> filesPaused = new ArrayList<>();
    private ArrayList<Long> pauseDurations = new ArrayList<>();

    // Binder given to clients
    private final IBinder mBinder = new LocalBinder();


    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mDatabase = new DBHelper(getApplicationContext());

    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if(intent != null){
            Bundle bundle = intent.getExtras();
            mCustomFileName = bundle.getString("fliename");
        }

        Toast.makeText(this,  mCustomFileName , Toast.LENGTH_SHORT).show();
        boolean containsCommand = MyIntentBuilder.containsCommand(intent);
        Log.d(LOG_TAG, String.format(
                "Service in [%s] state. cmdId: [%s]. startId: [%d]",
                state,
                containsCommand ? MyIntentBuilder.getCommand(intent) : "N/A",
                startId));
        routeIntentToCommand(intent);

        // We want this service to continue running until it is explicitly stopped, so return sticky
        return START_STICKY;
    }

    private void routeIntentToCommand(@Nullable Intent intent) {
        if (intent != null) {
            // process command
            if (MyIntentBuilder.containsCommand(intent)) {
                processCommand(MyIntentBuilder.getCommand(intent));
            }
            // process message
            if (MyIntentBuilder.containsMessage(intent)) {
                processMessage(MyIntentBuilder.getMessage(intent));
            }
        }
    }

    private void processMessage(String message) {
        try {
            Log.d(LOG_TAG, String.format("doMessage: message from client: '%s'", message));
            // TODO
        } catch (Exception e) {
            Log.e(LOG_TAG, "processMessage: exception", e);
        }
    }

    private void processCommand(@Command int command) {
        try {
            switch (command) {
                case Command.START:
                    startRecording();
                    break;
                case Command.PAUSE:
                    pauseRecording();
                    break;
                case Command.STOP:
                    stopService();
                    break;
                case Command.RELEASE:
                    release();
                    break;
     
            }
        } catch (Exception e) {
            Log.e(LOG_TAG, "processCommand: exception", e);
        }
    }

    public void stopService() {
        Log.d(LOG_TAG, "RecordingService#stopService()");
        stopRecording();
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        if (mRecorder != null) {
            stopRecording();
        }

        super.onDestroy();
        deleteFiles();
    }

    public void setFileNameAndPath(boolean isFilePathTemp, String saveFileName) {
        if (isFilePathTemp) {
            //here we can implement custom name for recorded file by edit text



            mFileName = getString(R.string.default_file_name) + (++tempFileCount) + "_" + ".tmp";

            mFilePath = Paths.combine(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC).getAbsolutePath(),
                    Paths.SOUND_RECORDER_FOLDER, mFileName);
        } else {
            int count = 0;
            File f;

            do {
                ++count;
                
                mFileName =
                        getString(R.string.default_file_name) + "_" + (mDatabase.getCount() + count) + ".mp3";

                mFilePath = Paths.combine(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                        Paths.SOUND_RECORDER_FOLDER, mFileName);

                f = new File(mFilePath);
            } while (f.exists() && !f.isDirectory());
        }
    }

    /**
     * Start or resume sound recording.
     */
    public void startRecording() {
        if (state == RecorderState.RECORDING || state == RecorderState.PREPARING)
            return;
        changeStateTo(RecorderState.PREPARING);
        boolean isTemporary = true;
        setFileNameAndPath(isTemporary, null);
        // Configure the MediaRecorder for a new recording

        mRecorder = new MediaRecorder();
        mRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        mRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        mRecorder.setOutputFile(mFilePath);
        mRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        mRecorder.setAudioChannels(1);

        if (MySharedPreferences.getPrefHighQuality(this)) {
            mRecorder.setAudioSamplingRate(44100);
            mRecorder.setAudioEncodingBitRate(192000);
        }
        try {
            final long totalDurationMillis = getTotalDurationMillis();
            mRecorder.prepare();
            mRecorder.start();
            if (state != RecorderState.PAUSED)
                NotificationCompatPie.createNotification(this);
            changeStateTo(RecorderState.RECORDING);
            mStartingTimeMillis = SystemClock.elapsedRealtime();
            EventBroadcaster.startRecording(this, mStartingTimeMillis - totalDurationMillis);
        }
        catch (IOException e) {
            changeStateTo(RecorderState.STOPPED);
            EventBroadcaster.stopRecording(this);
            Log.e(LOG_TAG, "prepare() failed", e);
            EventBroadcaster.send(this, getString(R.string.error_unknown));
        } catch (IllegalStateException e) {
            changeStateTo(RecorderState.STOPPED);
            EventBroadcaster.stopRecording(this);
            Log.e(LOG_TAG, "start() failed", e);
            EventBroadcaster.send(this, getString(R.string.error_mic_is_busy));
        }


    }


    public void pauseRecording() {
        if (state != RecorderState.RECORDING)
            return;
        changeStateTo(RecorderState.PREPARING);

        try {
            mElapsedMillis = (SystemClock.elapsedRealtime() - mStartingTimeMillis);
            pauseDurations.add(mElapsedMillis);
            mRecorder.stop(); //not using pause method because of api level and dash in final file is necessary because of media recorder library its default because we are using api level 16 these issues will remain until app is set to api level 24

            changeStateTo(RecorderState.PAUSED);

            filesPaused.add(mFilePath);
        } catch (IllegalStateException exc) {
            changeStateTo(RecorderState.RECORDING);
            Log.e(LOG_TAG, "stop() failed", exc);
        }

    }

    public void release() {
        try {
            changeStateTo(RecorderState.STOPPED);
            mRecorder.reset();
            mRecorder.release();
            mElapsedMillis = 0;
            mStartingTimeMillis = 0;
            pauseDurations.clear();
            mRecorder = null;
            filesPaused.clear();
            if(filesPaused.isEmpty()) {
            }
            mFilePath = null;
            mFileName = null;


            NotificationCompatPie.DestroyNotification(this);

        } catch (RuntimeException exc) {
            // RuntimeException is thrown when stop() is called immediately after start().
            // In this case the output file is not properly constructed ans should be deleted.
            Log.e(LOG_TAG, "RuntimeException: stop() is called immediately after start()", exc);
            // TODO delete temporary output file
        } finally {
            mRecorder = null;
            changeStateTo(RecorderState.STOPPED);
            EventBroadcaster.stopRecording(this);
        }

    }



    public void deleteFiles()
    {
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            Paths.SOUND_RECORDER_FOLDER);
        if (!dir.exists())
            return;
        File[] files = dir.listFiles();
        for (File file : files)
        {
            if (file.isFile() && file.getName().endsWith(".tmp"))
            {
                boolean result = file.delete();
                Log.d("TAG", "Deleted:" + result);
            }
        }
    }



    public void stopRecording() {



        if (state == RecorderState.STOPPED ) {
            Log.wtf(LOG_TAG, "stopRecording: already STOPPED.");
            return;
        }
        if (state == RecorderState.PREPARING)
            return;
        final RecorderState stateBefore = state;
        changeStateTo(RecorderState.PREPARING);

        if (stateBefore == RecorderState.RECORDING)
            filesPaused.add(mFilePath);

        boolean isTemporary = false;
        setFileNameAndPath(isTemporary, null);

           try {
            if (stateBefore != RecorderState.PAUSED) {
                mElapsedMillis = (SystemClock.elapsedRealtime() - mStartingTimeMillis);
                mRecorder.stop();
            }
            mRecorder.release();

        } catch (RuntimeException exc) {
            // RuntimeException is thrown when stop() is called immediately after start().
            // In this case the output file is not properly constructed ans should be deleted.
            Log.e(LOG_TAG, "RuntimeException: stop() is called immediately after start()", exc);
            // TODO delete temporary output file
        } finally {
            mRecorder = null;
               //mDatabase.removeItemWithId(position);
               changeStateTo(RecorderState.STOPPED);
            EventBroadcaster.stopRecording(this);
        }
        if (filesPaused != null && !filesPaused.isEmpty()) {
            if (makeSingleFile(filesPaused)) {
                mElapsedMillis = 0;
                for (long duration : pauseDurations)
                    mElapsedMillis += duration;
            }
        }
        try {
            mDatabase.addRecording(mFileName, mFilePath, mElapsedMillis);
        } catch (Exception e) {
            Log.e(LOG_TAG, "exception", e);
        }

    }


    /**
     * collect temp generated files because of pause to one target file
     *
     * @param filesPaused contains all temp files due to pause
     */
    private boolean makeSingleFile(ArrayList<String> filesPaused) {
        ArrayList<Track> tracks = new ArrayList<>();
        Movie finalMovie = new Movie();
        for (String filePath : filesPaused) {
            try {
                Movie movie = MovieCreator.build(filePath);
                List<Track> movieTracks = movie.getTracks();
                tracks.addAll(movieTracks);
            } catch (IOException e) {
                e.printStackTrace();
                return false;
            } catch (NullPointerException exc) {
                Log.wtf(LOG_TAG, "Caught NPE from MovieCreator#build()");
            }
        }

        if (tracks.size() > 0) {
            try {
                finalMovie.addTrack(new AppendTrack(tracks.toArray(new Track[0])));
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        final Container mp4file;
        final FileChannel fc;
        try {
            mp4file = new DefaultMp4Builder().build(finalMovie);
            fc = new FileOutputStream(new File(mFilePath)).getChannel();
        } catch (NoSuchElementException exc) {
            Log.wtf(LOG_TAG, "Caught NoSuchElementException from DefaultMp4Builder#build()", exc);
            return false;
        } catch (FileNotFoundException e) {
            e.printStackTrace();
            return false;
        }

        boolean ok = true;
        try {
            mp4file.writeContainer(fc);
        } catch (IOException e) {
            e.printStackTrace();
            ok = false;
        } finally {
            try {
                fc.close();
            } catch (IOException exc) {
                exc.printStackTrace();
                ok = false;
            }
        }

        return ok;
    }

    public long getElapsedMillis() {
        return mElapsedMillis;
    }

    public long getStartingTimeMillis() {
        return mStartingTimeMillis;
    }

    public long getTotalDurationMillis() {
        long total = 0;
        if (pauseDurations == null || pauseDurations.isEmpty()) {
            total += mElapsedMillis;
        } else {
            for (long duration : pauseDurations)
                total += duration;
        }
        if (state == RecorderState.RECORDING) {
            total += (SystemClock.elapsedRealtime() - mStartingTimeMillis);
        }

        return total;
    }

    public RecorderState getState() {
        return state;
    }

    private void changeStateTo(RecorderState newState) {
        if (state == RecorderState.PREPARING && newState == RecorderState.PREPARING)
            throw new IllegalStateException();
        state = newState;

    }

    /**
     * Class used for the client Binder.  Because we know this service always
     * runs in the same process as its clients, we don't need to deal with IPC.
     */
    public class LocalBinder extends Binder {
        public RecordingService getService() {
            // Return this instance of LocalService so clients can call public methods
            return RecordingService.this;
        }
    }
}
