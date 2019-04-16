package com.browsio.soundrecorder.fragments;

import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.os.FileObserver;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import com.browsio.soundrecorder.activities.SettingsActivity;
import com.browsio.soundrecorder.adapters.FileViewerAdapter;
import com.browsio.soundrecorder.util.Paths;

import java.io.File;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import by.browsio.soundrecorder.R;

/**
 * Created by Daniel on 12/23/2014.
 */
public class FileViewerFragment extends Fragment {
    private static final String LOG_TAG = "FileViewerFragment";

    private FileViewerAdapter mFileViewerAdapter;

    public static FileViewerFragment newInstance() {
        FileViewerFragment f = new FileViewerFragment();
        Bundle b = new Bundle();
        f.setArguments(b);

        return f;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        observer.startWatching();
//        deleteAll.startWatching();
        setHasOptionsMenu(true);
    }

    @Override
    public void onDestroy() {
        observer.stopWatching();
//        deleteAll.stopWatching();
        super.onDestroy();
    }


    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_file_viewer, container, false);


        RecyclerView mRecyclerView = v.findViewById(R.id.recyclerView);
        mRecyclerView.setHasFixedSize(true);
        //newest to oldest order (database stores from oldest to newest)
        final LinearLayoutManager llm = new LinearLayoutManager(
                getActivity(), RecyclerView.VERTICAL, true);
        llm.setStackFromEnd(true);

        mRecyclerView.setLayoutManager(llm);
        mRecyclerView.setItemAnimator(new DefaultItemAnimator());

        mFileViewerAdapter = new FileViewerAdapter(getActivity(), llm);
        mRecyclerView.setAdapter(mFileViewerAdapter);

        return v;
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.menu_main2,menu);
        super.onCreateOptionsMenu(menu, inflater);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_deleteAll:
                AlertDialog.Builder confirmDelete = new AlertDialog.Builder(getActivity());
                confirmDelete.setTitle(getActivity().getString(R.string.dialog_clear_data));
                confirmDelete.setMessage(getActivity().getString(R.string.dialog_clear_data_sumry));
                confirmDelete.setCancelable(true);
                confirmDelete.setPositiveButton(getActivity().getString(R.string.dialog_action_yes_delete),
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int id) {
                                File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                                        Paths.SOUND_RECORDER_FOLDER);
                                if (dir.isDirectory())
                                {
                                    String[] children = dir.list();
                                    for (int i = 0; i < children.length; i++)
                                    {
                                        new File(dir, children[i]).delete();
                                    }
                                }
                                observer.startWatching();
                                mFileViewerAdapter.note();
                            }
                        });
                confirmDelete.setNegativeButton(getActivity().getString(R.string.dialog_action_no),
                        new FileViewerAdapter.CancelDialogListener());

                AlertDialog alert = confirmDelete.create();
                alert.show();
                return true;

            case R.id.action_settings:
                Intent i = new Intent(getActivity(), SettingsActivity.class);
                startActivity(i);
                return true;
        }
        return super.onOptionsItemSelected(item);
    }



    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mFileViewerAdapter = null;
    }




    private final FileObserver observer =
            new FileObserver(Paths.combine(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                    Paths.SOUND_RECORDER_FOLDER)) {
                // set up a file observer to watch this directory on sd card
                @Override
                public void onEvent(int event, String file) {
                    if (event == FileObserver.DELETE) {
                        // user deletes a recording file out of the app

                        final String filePath = Paths.combine(
                                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                                Paths.SOUND_RECORDER_FOLDER, file);
                        Log.d(LOG_TAG, "File deleted [" + filePath + "]");

                        final File filespath = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                                Paths.SOUND_RECORDER_FOLDER);
                        if (filespath.isDirectory()){
                            if (filePath.isEmpty()){
                                mFileViewerAdapter.notifyDataSetChanged();
//                                mFileViewerAdapter.removeOutOfApp();
                            }

                        }
                        // remove file from database and recyclerview
                        mFileViewerAdapter.removeOutOfApp(filePath);
                        mFileViewerAdapter.notifyDataSetChanged();
                    }

                }
            };

//    private final FileObserver deleteAll =
//            new FileObserver(Paths.combine(
//            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
//    Paths.SOUND_RECORDER_FOLDER)) {
//        // set up a file observer to watch this directory on sd card
//        @Override
//        public void onEvent(int event, String file) {
//            if (event == FileObserver.DELETE) {
//                // user deletes a recording file out of the app
//
//                final String filePath = Paths.combine(
//                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
//                        Paths.SOUND_RECORDER_FOLDER);
//                if  (filePath.isEmpty())
//                Log.d(LOG_TAG, "File deleted [" + filePath + "]");
//                {
//                    // remove file from database and recyclerview
////                    mFileViewerAdapter.removeOutOfApp();
//                mFileViewerAdapter.notifyDataSetChanged();
//                }
//            }
//
//        }
//    };

}





