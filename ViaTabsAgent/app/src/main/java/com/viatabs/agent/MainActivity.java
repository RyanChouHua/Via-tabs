package com.viatabs.agent;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView view = new TextView(this);
        view.setPadding(32, 32, 32, 32);
        view.setText("ViaTabsAgent\nEnable this module for mark.via or mark.via.gp in LSPosed/LSPatch.");
        setContentView(view);
    }
}
