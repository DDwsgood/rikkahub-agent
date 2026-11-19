package org.rikkahub.calendarprobe;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.provider.CalendarContract;
import android.provider.CalendarContract.Events;
import android.util.Log;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.text.DateFormat;
import java.util.Date;

public final class ProbeActivity extends Activity {
    private static final String TAG = "CalendarReminderProbe";
    private static final long MINUTE_MS = 60_000L;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(40, 160, 40, 40);

        TextView status = new TextView(this);
        status.setText("Opens a test event in Calendar. Choose a local calendar and an at-start reminder, then save.");
        content.addView(status);
        addLaunchButton(content, status, "URI only", false);
        addLaunchButton(content, status, "URI + event MIME", true);
        setContentView(content);
    }

    private void addLaunchButton(LinearLayout content, TextView status, String label, boolean includeMime) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(view -> launchCalendar(status, label, includeMime));
        content.addView(button);
    }

    private void launchCalendar(TextView status, String label, boolean includeMime) {
        long begin = ((System.currentTimeMillis() / MINUTE_MS) + 3) * MINUTE_MS;
        Intent intent = new Intent(Intent.ACTION_INSERT);
        if (includeMime) {
            intent.setDataAndType(Events.CONTENT_URI, "vnd.android.cursor.dir/event");
        } else {
            intent.setData(Events.CONTENT_URI);
        }
        intent.putExtra(Events.TITLE, "RikkaHub reminder probe " + (begin / MINUTE_MS));
        intent.putExtra(Events.DESCRIPTION, "Disposable Calendar reminder test. No invitees.");
        intent.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin);
        intent.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, begin + 10 * MINUTE_MS);
        intent.putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, false);

        try {
            startActivity(intent);
            String result = label + " opened Calendar at " + DateFormat.getTimeInstance().format(new Date(begin));
            status.setText(result);
            Log.i(TAG, result + "; begin=" + begin);
        } catch (ActivityNotFoundException e) {
            String result = label + ": no Calendar accepts this intent";
            status.setText(result);
            Log.i(TAG, result);
        }
    }
}
