package com.browsio.soundrecorder.fragments;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Chronometer;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.browsio.soundrecorder.RecorderState;
import com.browsio.soundrecorder.activities.SettingsActivity;
import com.browsio.soundrecorder.listeners.OnSingleClickListener;
import com.browsio.soundrecorder.services.RecordingService;
import com.browsio.soundrecorder.util.Command;
import com.browsio.soundrecorder.util.EventBroadcaster;
import com.browsio.soundrecorder.util.MyIntentBuilder;
import com.browsio.soundrecorder.util.Paths;
import com.browsio.soundrecorder.util.PermissionsHelper;
import com.browsio.soundrecorder.util.ScreenLock;
import com.budiyev.android.circularprogressbar.CircularProgressBar;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.io.File;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import by.browsio.soundrecorder.R;

/**
 * A simple {@link Fragment} subclass.
 * Activities that contain this fragment must implement the
 * to handle interaction events.
 * Use the {@link RecordFragment#newInstance} factory method to
 * create an instance of this fragment.
 */
public class RecordFragment extends Fragment {
    private static final String LOG_TAG = RecordFragment.class.getSimpleName();
    private static final int MY_PERMISSIONS_REQUEST_RECORD_AUDIO = 1;
    private static final int MY_PERMISSIONS_REQUEST_RECORD_AUDIO_RESUME = 2;

    //Recording controls
    private FloatingActionButton mRecordButton = null, mPauseButton = null;
    //    private MaterialButton ;
    private boolean isRecordButtonInState1 = true;  // true = record, false = stop
    private boolean isPauseButtonInState1 = true;   // true = pause, false = resume

    // ProgressBar around Chronometer
    private CircularProgressBar mProgressBar;
    private TextView mRecordingPrompt;
    private int mRecordPromptCount = 0;

    private Chronometer mChronometer = null;
    long timeWhenPaused = 0; //stores time when user clicks pause button

    // Defines callbacks for service binding, passed to bindService()
    private ServiceConnection mConnection;
    private RecordingService mRecordingService;
    private BroadcastReceiver mMessageReceiver = null;

    /**
     * Use this factory method to create a new instance of
     * this fragment using the provided parameters.
     *
     * @return A new instance of fragment Record_Fragment.
     */
    public static RecordFragment newInstance() {
        RecordFragment f = new RecordFragment();
        Bundle b = new Bundle();
        f.setArguments(b);

        return f;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        mMessageReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final RecorderState newState = (RecorderState) intent.getSerializableExtra(
                        EventBroadcaster.NEW_STATE);
                if (RecorderState.STOPPED.equals(newState)) {
                    updateUI(newState, SystemClock.elapsedRealtime());
                } else if (RecorderState.RECORDING.equals(newState)) {
                    long chronometerTime = intent.getLongExtra(EventBroadcaster.CHRONOMETER_TIME,
                            SystemClock.elapsedRealtime());
                    updateUI(newState, chronometerTime);
                }
            }
        };


    }
    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.menu_main,menu);
        super.onCreateOptionsMenu(menu, inflater);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_settings:
                Intent i = new Intent(getActivity(), SettingsActivity.class);
                startActivity(i);
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        final Intent intent = new Intent(context, RecordingService.class);
        tryBindService(context, intent);
    }

    @Override
    public void onDetach() {
        super.onDetach();
        tryUnbindService(getContext());
    }

    private void tryBindService(Context context, Intent intent) {
        if (mConnection == null) {
            mConnection = new ServiceConnection() {

                @Override
                public void onServiceConnected(ComponentName className, IBinder service) {
                    // We've bound to LocalService, cast the IBinder and get LocalService instance
                    RecordingService.LocalBinder binder = (RecordingService.LocalBinder) service;
                    mRecordingService = binder.getService();
                    Log.i(LOG_TAG, "RecordFragment ServiceConnection#onServiceConnected");

                    long chronometerTime = SystemClock.elapsedRealtime() - mRecordingService.getTotalDurationMillis();
                    updateUI(mRecordingService.getState(), chronometerTime);
                }

                @Override
                public void onServiceDisconnected(ComponentName arg0) {
                    Log.i(LOG_TAG, "RecordFragment ServiceConnection#onServiceDisconnected");
                    mRecordingService = null;
                }
            };

            context.bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
        }
    }

    private void tryUnbindService(Context context) {
        if (context == null)
            Log.w(LOG_TAG, "tryUnbindService: context is null");
        if (mConnection != null) {
            context.unbindService(mConnection);
            mConnection = null;
        }
    }

    @Override
    @Nullable
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View recordView = inflater.inflate(R.layout.fragment_record, container, false);

        mChronometer = recordView.findViewById(R.id.chronometer);
        //update recording prompt text
        mRecordingPrompt = recordView.findViewById(R.id.recording_status_text);

        mRecordButton = recordView.findViewById(R.id.btnRecord);
        mRecordButton.setOnClickListener(createRecordButtonClickListener());

        mProgressBar = recordView.findViewById(R.id.recordProgressBar);
        mPauseButton = recordView.findViewById(R.id.btnPause);
        mPauseButton.setVisibility(View.GONE); //hide pause button before recording starts
        mPauseButton.setOnClickListener(createPauseButtonClickListener());

        return recordView;
    }

    private final Chronometer.OnChronometerTickListener listener = new Chronometer.OnChronometerTickListener() {
        @Override
        public void onChronometerTick(Chronometer chronometer) {
            if (mRecordPromptCount == 0) {
                mRecordingPrompt.setText(getString(R.string.record_in_progress));
            } else if (mRecordPromptCount == 1) {
                mRecordingPrompt.setText(getString(R.string.record_in_progress));
            } else if (mRecordPromptCount == 2) {
                mRecordingPrompt.setText(getString(R.string.record_in_progress));
                mRecordPromptCount = -1;
            }

            ++mRecordPromptCount;
        }
    };

    private View.OnClickListener createPauseButtonClickListener() {
        return new OnSingleClickListener() {
            @Override
            public void onSingleClick(View v) {
                if (isPauseButtonInState1) {
                    // pause recording
                    mRecordingService.pauseRecording();
                    long chronometerTime = SystemClock.elapsedRealtime() - mRecordingService.getTotalDurationMillis();
                    updateUI(RecorderState.PAUSED, chronometerTime);
                } else {
                    if (PermissionsHelper.checkAndRequestPermissions(RecordFragment.this,
                            MY_PERMISSIONS_REQUEST_RECORD_AUDIO)) {
                        resumeRecording();
                    }
                }

            }
        };
    }

    private View.OnClickListener createRecordButtonClickListener() {
        return new OnSingleClickListener() {
            @Override
            public void onSingleClick(View v) {
                if (isRecordButtonInState1) {
                    if (PermissionsHelper.checkAndRequestPermissions(
                            RecordFragment.this, MY_PERMISSIONS_REQUEST_RECORD_AUDIO)) {
                        startRecording();
                    }
                } else {
                    stopRecording();
                }
            }
        };
    }

    private Intent getStartServiceIntent() {
        return MyIntentBuilder
                .getInstance(requireActivity(), RecordingService.class)
                .setCommand(Command.START)
                .build();
    }

    private Intent getStopServiceIntent() {
        return MyIntentBuilder
                .getInstance(requireActivity(), RecordingService.class)
                .setCommand(Command.STOP)
                .build();
    }

    private Intent getPauseServiceIntent() {
        return MyIntentBuilder
                .getInstance(requireActivity(), RecordingService.class)
                .setCommand(Command.PAUSE)
                .build();
    }

    private Intent getReleaseServiceIntent() {
        return MyIntentBuilder
                .getInstance(requireActivity(), RecordingService.class)
                .setCommand(Command.RELEASE)
                .build();
    }

    private void updateUI(RecorderState state, long chronometerBaseTime) {
        if (getActivity() == null || !isAdded()) {
            Log.i(LOG_TAG, "RecordFragment#updateUI: RecordFragment is not attached to an Activity");
            return;
        }
        Log.i(LOG_TAG, "RecordFragment#updateUI: new state is " + state + ", time is " + chronometerBaseTime + " ms");

        switch (state) {

            case STOPPED:
                mRecordButton.show();
                mRecordButton.setImageResource(R.drawable.ic_mic_white_36dp);
                mPauseButton.setVisibility(View.GONE);
                timeWhenPaused = 0;
                mRecordingPrompt.setText(getString(R.string.record_prompt));

                isPauseButtonInState1 = true;
                isRecordButtonInState1 = true;

                mChronometer.setOnChronometerTickListener(null);
                mChronometer.setBase(SystemClock.elapsedRealtime());
                mChronometer.stop();
                mProgressBar.setIndeterminate(false);
                break;

            case PREPARING:
                mRecordingPrompt.setText(getString(R.string.wait));
                mProgressBar.setIndeterminate(true);
                break;

            case RECORDING:
                mRecordButton.setImageResource(R.drawable.ic_media_stop);
                mPauseButton.setImageResource(R.drawable.ic_media_pause);

//                mPauseButton.setText(getString(R.string.pause_recording_button).toUpperCase());
                mPauseButton.setVisibility(View.VISIBLE);
                mRecordingPrompt.setText(getString(R.string.record_in_progress));

                isPauseButtonInState1 = true;
                isRecordButtonInState1 = false;

                mChronometer.setBase(chronometerBaseTime);
                mChronometer.setOnChronometerTickListener(listener);
                mChronometer.start();

                mProgressBar.setIndeterminate(true);
                break;

            case PAUSED:
                mRecordButton.setImageResource(R.drawable.ic_media_stop);
                mPauseButton.setImageResource(R.drawable.ic_mic_white_36dp);

//                mPauseButton.setText(getString(R.string.resume_recording_button).toUpperCase());
                mPauseButton.setVisibility(View.VISIBLE);
                mRecordingPrompt.setText(getString(R.string.record_paused));

                isPauseButtonInState1 = false;
                isRecordButtonInState1 = false;

                mChronometer.setOnChronometerTickListener(null);
                mChronometer.setBase(chronometerBaseTime);
                mChronometer.stop();

                mProgressBar.setIndeterminate(false);
                break;

            case RELEASE:
                mRecordButton.show();
                mRecordButton.setImageResource(R.drawable.ic_mic_white_36dp);
                mPauseButton.setVisibility(View.GONE);
                timeWhenPaused = 0;
                mRecordingPrompt.setText(getString(R.string.record_prompt));

                isPauseButtonInState1 = true;
                isRecordButtonInState1 = true;

                mChronometer.setOnChronometerTickListener(null);
                mChronometer.setBase(SystemClock.elapsedRealtime());
                mChronometer.stop();
                mProgressBar.setIndeterminate(false);
                break;
        }
    }

    /**
     * Stop recording
     */
    private void stopRecording() {
        LayoutInflater inflater = getLayoutInflater();
        View saveDiscardlayout = inflater.inflate(R.layout.save_dialog_layout, null);
        final EditText saveName = saveDiscardlayout.findViewById(R.id.setFile_name);


        final FragmentActivity activity = getActivity();
        activity.startService(getPauseServiceIntent());

        final androidx.appcompat.app.AlertDialog.Builder saveDiscard = new androidx.appcompat.app.AlertDialog.Builder(getActivity());
        saveDiscard.setCancelable(false);
        saveDiscard.setView(saveDiscardlayout);
        saveDiscard.setPositiveButton(getActivity().getString(R.string.save_file), new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int id) {
                try {
                    activity.startService(getStopServiceIntent());
                    Toast.makeText(mRecordingService, "file name is" + fileName, Toast.LENGTH_SHORT).show();
                    if (activity == null) {
                        Log.wtf(LOG_TAG, "Recording saved");
                        return;
                    }
                    tryUnbindService(activity);
                } catch (Exception e) {
                    Log.e(LOG_TAG, "exception", e);
                }

                dialog.cancel();
            }
        });
        saveDiscard.setNegativeButton(getActivity().getString(R.string.discard_file),
                new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        //TODO pause discard timer reset
                       activity.startService(getReleaseServiceIntent());

                        mRecordingService.deleteFiles();

                        if (activity != null) {
                            Log.wtf(LOG_TAG, "Recording discarded");
                            return;
                        }
                        tryUnbindService(activity);

                        dialogInterface.cancel();
                    }
                });

        androidx.appcompat.app.AlertDialog alert = saveDiscard.create();
        alert.show();
        chronometer();
        ScreenLock.allowScreenTurnOff(activity);
    }


    /**
     * chronometer stop
     **/
    public void chronometer() {
        mChronometer.stop();
        mChronometer.setBase(SystemClock.elapsedRealtime());
        mProgressBar.setIndeterminate(false);
        mChronometer.setOnChronometerTickListener(null);
        mProgressBar.setIndeterminate(false);
        mRecordButton.show();
        mRecordButton.setImageResource(R.drawable.ic_mic_white_36dp);
        mPauseButton.setVisibility(View.GONE);
        timeWhenPaused = 0;
        mRecordingPrompt.setText(getString(R.string.record_prompt));


    }

    /**
     * Start recording
     */
    private boolean startRecording() {
        final File folder = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                Paths.SOUND_RECORDER_FOLDER);
        if (!folder.exists()) {
            // a folder for sound recordings doesn't exist -> create the folder
            boolean ok = Paths.isExternalStorageWritable() && folder.mkdir();
            if (!ok) {
                EventBroadcaster.send(getContext(), R.string.error_mkdir);
                return false;
            }
        }

        final FragmentActivity activity = getActivity();
        if (activity == null) {
            Log.wtf(LOG_TAG, "RecordFragment#startRecording(): failed to start recording, getActivity() returns null.");
            return false;
        }
        // start RecordingService in foreground
        final Intent intent = getStartServiceIntent();
        final ComponentName componentName;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            componentName = activity.startForegroundService(intent);
        } else {
            componentName = activity.startService(intent);
        }
        tryBindService(activity, intent);
        ScreenLock.keepScreenOn(activity);

        if (componentName != null) {
            updateUI(RecorderState.PREPARING, SystemClock.elapsedRealtime());
        }

        return true;
    }

    /**
     * Resume recording
     */
    private void resumeRecording() {
        long chronometerTime = SystemClock.elapsedRealtime() - mRecordingService.getTotalDurationMillis();
        mRecordingService.startRecording();
        updateUI(RecorderState.PREPARING, chronometerTime);
    }

    @Override
    public void onResume() {
        super.onResume();
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(mMessageReceiver,
                new IntentFilter(EventBroadcaster.CHANGE_STATE));
    }

    @Override
    public void onPause() {
        super.onPause();
        try {
            LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(mMessageReceiver);
        } catch (Exception exc) {
            Log.e(LOG_TAG, "Error unregistering MessageReceiver", exc);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String permissions[],
                                           @NonNull int[] grantResults) {
        switch (requestCode) {
            case MY_PERMISSIONS_REQUEST_RECORD_AUDIO: {
                // If request is cancelled, the result arrays are empty.
                if (allPermissionsGranted(grantResults)) {
                    // permissions were granted, yay!
                    startRecording();
                } else {
                    EventBroadcaster.send(getContext(), R.string.error_no_permission_granted_record);
                }
                break;
            }

            case MY_PERMISSIONS_REQUEST_RECORD_AUDIO_RESUME: {
                // If request is cancelled, the result arrays are empty.
                if (allPermissionsGranted(grantResults)) {
                    // permission was granted, yay!
                    resumeRecording();
                } else {
                    EventBroadcaster.send(getContext(), R.string.error_no_permission_granted_record);
                }
                break;
            }
        }
    }

    private boolean allPermissionsGranted(int[] grantResults) {
        if (grantResults == null || grantResults.length == 0)
            return false;

        boolean ok = true;
        for (int grantResult : grantResults) {
            ok &= (grantResult == PackageManager.PERMISSION_GRANTED);
        }
        return ok;
    }

}