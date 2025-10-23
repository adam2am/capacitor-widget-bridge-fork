package de.kisimedia.plugins.widgetbridgeplugin;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import android.content.Intent;
import android.os.Build;

import androidx.annotation.RequiresApi;

import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@CapacitorPlugin(name = "WidgetBridgePlugin")
public class WidgetBridgePlugin extends Plugin {

    private static final String KEY_GROUP = "group";
    private static final String KEY_RESULTS = "results";

    private String[] registeredWidgetProviders = new String[]{};

    private SharedPreferences getPrefs(String group) {
        return getContext().getSharedPreferences(group, Context.MODE_PRIVATE);
    }

    @PluginMethod
    public void getItem(PluginCall call) {
        String key = call.getString("key");
        String group = call.getString(KEY_GROUP);

        if (key == null || group == null) {
            call.reject("Missing key or group");
            return;
        }

        SharedPreferences prefs = getPrefs(group);
        // Handle any primitive type, not just String, to be more robust.
        Object value = prefs.getAll().get(key);

        JSObject result = new JSObject();
        result.put(KEY_RESULTS, value != null ? value : JSONObject.NULL);
        call.resolve(result);
    }

    @PluginMethod
    public void setItem(PluginCall call) {
        String key = call.getString("key");
        String group = call.getString(KEY_GROUP);
        String value = call.getString("value");

        if (key == null || group == null || value == null) {
            call.reject("Missing key, group, or value");
            return;
        }

        SharedPreferences.Editor editor = getPrefs(group).edit();
        editor.putString(key, value);
        editor.apply();
        call.resolve(new JSObject().put(KEY_RESULTS, true));
    }

    @PluginMethod
    public void removeItem(PluginCall call) {
        String key = call.getString("key");
        String group = call.getString(KEY_GROUP);

        if (key == null || group == null) {
            call.reject("Missing key or group");
            return;
        }

        SharedPreferences.Editor editor = getPrefs(group).edit();
        editor.remove(key);
        editor.apply();
        call.resolve(new JSObject().put(KEY_RESULTS, true));
    }

    @PluginMethod
    public void reloadAllTimelines(PluginCall call) {
        Context context = getContext();
        List<String> errors = new ArrayList<>();

        for (String className : registeredWidgetProviders) {
            try {
                Class<?> widgetClass = Class.forName(className);
                ComponentName componentName = new ComponentName(context, widgetClass);
                int[] ids = AppWidgetManager.getInstance(context).getAppWidgetIds(componentName);
                if (ids.length > 0) {
                    Intent updateIntent = new Intent(context, widgetClass);
                    updateIntent.setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE);
                    updateIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids);
                    context.sendBroadcast(updateIntent);
                }
            } catch (ClassNotFoundException e) {
                String errorMsg = "Widget class not found: " + className;
                errors.add(errorMsg);
            }
        }

        if (errors.isEmpty()) {
            call.resolve(new JSObject().put(KEY_RESULTS, true));
        } else {
            call.reject("Failed to reload some timelines: " + String.join(", ", errors));
        }
    }

    @PluginMethod
    public void reloadTimelines(PluginCall call) {
        String kind = call.getString("ofKind");

        if (kind == null) {
            call.reject("Missing ofKind parameter");
            return;
        }

        Context context = getContext();
        try {
            Class<?> widgetClass = Class.forName(kind);
            ComponentName componentName = new ComponentName(context, widgetClass);
            int[] ids = AppWidgetManager.getInstance(context).getAppWidgetIds(componentName);
            if (ids.length > 0) {
                Intent updateIntent = new Intent(context, widgetClass);
                updateIntent.setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE);
                updateIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids);
                context.sendBroadcast(updateIntent);
            }
            call.resolve(new JSObject().put(KEY_RESULTS, true));
        } catch (ClassNotFoundException e) {
            call.reject("Widget class not found: " + kind);
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    @PluginMethod
    public void setRegisteredWidgets(PluginCall call) throws JSONException {
        JSArray widgets = call.getArray("widgets");
        if (widgets == null) {
            call.reject("Missing widgets array");
            return;
        }

        List<String> list = widgets.toList().stream()
            .filter(String.class::isInstance)
            .map(String.class::cast)
            .collect(Collectors.toList());

        registeredWidgetProviders = list.toArray(new String[0]);
        call.resolve(new JSObject().put(KEY_RESULTS, true));
    }

    @PluginMethod
    public void getCurrentConfigurations(PluginCall call) {
        // Nicht direkt umsetzbar wie in iOS
        call.resolve(new JSObject().put(KEY_RESULTS, "not supported"));
    }

    @PluginMethod
    public void requestWidget(PluginCall call) {
        String className = call.getString("className");

        if (className == null || className.isEmpty()) {
            call.reject("Missing or empty 'className' parameter");
            return;
        }

        Context context = getContext();

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            call.reject("This feature requires Android O (API level 26) or higher.");
            return;
        }

        try {
            Class<?> widgetClass = Class.forName(className);
            ComponentName myWidgetProvider = new ComponentName(context, widgetClass);

            AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(context);

            if (appWidgetManager.isRequestPinAppWidgetSupported()) {
                Intent pinnedWidgetCallbackIntent = new Intent(context, widgetClass);
                PendingIntent successCallback = PendingIntent.getBroadcast(
                        context,
                        0,
                        pinnedWidgetCallbackIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT | 
                        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : PendingIntent.FLAG_IMMUTABLE)
                );

                appWidgetManager.requestPinAppWidget(myWidgetProvider, null, successCallback);
                call.resolve(new JSObject().put(KEY_RESULTS, true));
            } else {
                call.reject("Pinning not supported");
            }
        } catch (ClassNotFoundException e) {
            call.reject("Widget provider class not found: " + className);
        } catch (IllegalArgumentException e) {
            call.reject("Invalid argument: " + e.getMessage());
        } catch (Exception e) {
            call.reject("Unexpected error: " + e.getMessage());
        }
    }

}
