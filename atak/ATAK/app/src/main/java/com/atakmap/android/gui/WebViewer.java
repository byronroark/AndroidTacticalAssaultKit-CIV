
package com.atakmap.android.gui;

import android.content.DialogInterface;
import android.view.Window;
import android.webkit.MimeTypeMap;
import android.webkit.WebResourceResponse;
import android.widget.LinearLayout.LayoutParams;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebChromeClient;
import android.webkit.ConsoleMessage;
import android.webkit.WebViewClient;
import android.graphics.Bitmap;
import android.app.AlertDialog;
import android.content.Context;

import com.atakmap.coremap.io.FileIOProviderFactory;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;

public class WebViewer {
    public static String TAG = "WebViewer";

    /**
     * Given a uri and a context, render the uri in an about dialog.
     * @param uri the string uri for the content.
     * @param context the context to be used to construct the components.
     */
    public static void show(final String uri, final Context context) {
        show(uri, context, 100);
    }

    public static void show(final String uri, final Context context,
            int scale) {
        show(uri, context, scale, null);
    }

    /**
     * Given a uri and a context, render the uri in an about dialog.
     * @param uri the string uri for the content.
     * @param context the context to be used to construct the components.
     * @param scale the initial scale of the page
     * @param action the action to take when the dialog is dismissed.
     */
    public static void show(final String uri, final Context context,
            int scale, final Runnable action) {
        final WebView v = createWebView(uri, context);
        v.loadUrl(uri);
        v.setInitialScale(scale);
        displayDialog(v,context,action);
    }

    /**
     * Given a file and a context, render the file in an about dialog.
     * @param file the file for the content.
     * @param context the context to be used to construct the components.
     * @param scale the initial scale of the page
     */
    public static void show(final File file, final Context context,
                            int scale) {
        show(file, context, scale, null);
    }

    /**
     * Given a file and a context, render the uri in an about dialog.
     * 
     * @param file the file for the content.
     * @param context the context to be used to construct the components.
     * @param scale the initial scale of the page
     * @param action the action to take when the dialog is dismissed.
     */
    public static void show(final File file, final Context context,
            int scale, final Runnable action) {

        final WebView v = createWebView(context);
        try {
            v.loadUrl(file.toURL().toString());
        } catch (MalformedURLException e) {
            e.printStackTrace();
        }
        v.setInitialScale(scale);
        displayDialog(v, context, action);
    }

    /**
     * Makes a webview from a URI given the context
     * 
     * @param uri The uri of the data to display
     * @param context The context
     * @return The built WebView
     */
    private static WebView createWebView(final String uri,
            final Context context) {
        WebView htmlViewer = makeBaseWebView(context);
        htmlViewer.loadUrl(uri);
        return htmlViewer;
    }

    /**
     * Makes the base webview and returns it
     * 
     * @param context The context
     * @return The built base webview
     */
    private static WebView makeBaseWebView(Context context) {
        // must be created using the application context otherwise this will fail
        WebView htmlViewer = new WebView(context);
        htmlViewer.setVerticalScrollBarEnabled(true);
        htmlViewer.setHorizontalScrollBarEnabled(true);

        WebSettings webSettings = htmlViewer.getSettings();

        // do not enable per security guidelines
        // webSettings.setAllowFileAccessFromFileURLs(true);
        // webSettings.setAllowUniversalAccessFromFileURLs(true);

        webSettings.setBuiltInZoomControls(true);
        webSettings.setDisplayZoomControls(false);
        htmlViewer.setWebChromeClient(new ChromeClient());

        // cause subsequent calls to loadData not to fail - without this
        // the web view would remain inconsistent on subsequent concurrent opens
        htmlViewer.loadUrl("about:blank");
        htmlViewer.setWebViewClient(new Client());
        htmlViewer.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT));
        return htmlViewer;
    }

    /**
     * Creates a Webview given a file and context
     *
     * @param context the Context
     * @return The created webview
     */
    private static WebView createWebView(final Context context) {
        // must be created using the application context otherwise this will fail
        WebView htmlViewer = makeBaseWebView(context);
        // noinspection deprecation
        htmlViewer.setWebViewClient(new WebViewClient() {
            @SuppressWarnings("deprecation")
            @Override
            public WebResourceResponse shouldInterceptRequest(final WebView view, String url) {
                if (url.contains("file:///")) {
                    try {
                        try {
                            return new WebResourceResponse(getMimeType(url), "UTF-8",
                                    FileIOProviderFactory
                                            .getInputStream(new File(new URL(url).toURI())));
                        } catch (URISyntaxException e) {
                            e.printStackTrace();
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                return super.shouldInterceptRequest(view, url);
            }
        });
        return htmlViewer;
    }

    /**
     * Gets the Mime type for a url given
     * 
     * @param url The URL
     * @return The correct MIME type
     */
    private static String getMimeType(String url) {
        String type = null;
        String extension = MimeTypeMap.getFileExtensionFromUrl(url);
        if (extension != null) {
            if (extension.equals("js")) {
                return "text/javascript";
            } else if (extension.equals("woff")) {
                return "application/font-woff";
            } else if (extension.equals("woff2")) {
                return "application/font-woff2";
            } else if (extension.equals("ttf")) {
                return "application/x-font-ttf";
            } else if (extension.equals("eot")) {
                return "application/vnd.ms-fontobject";
            } else if (extension.equals("svg")) {
                return "image/svg+xml";
            }
            type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        }
        return type;
    }

    /**
     * Displays an alert dialog with a Webview as the content
     *
     * @param v - The webview
     * @param context - The context
     * @param action - The action to execute on "Ok"
     */
    private static void displayDialog(final WebView v, Context context, final Runnable action) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setView(v);
        builder.setPositiveButton(com.atakmap.app.R.string.ok,
                new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (action != null)
                            action.run();
                    }
                })
                .setOnCancelListener(
                        new DialogInterface.OnCancelListener() {
                            @Override
                            public void onCancel(DialogInterface dialog) {
                                if (action != null)
                                    action.run();
                            }
                        });

        final AlertDialog dialog = builder.create();

        dialog.show();

        Window w = dialog.getWindow();
        if (w != null)
            w.setLayout(LayoutParams.MATCH_PARENT,
                    LayoutParams.MATCH_PARENT);
    }

    private static class Client extends WebViewClient {
        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            //Log.d(TAG, "started retrieving: " + url);
            super.onPageStarted(view, url, favicon);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            //Log.d(TAG, "shouldOverride: " + url);
            return false;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            //Log.d(TAG, "ended retrieving: " + url);
            super.onPageFinished(view, url);
        }

    }

    private static class ChromeClient extends WebChromeClient {
        @Override
        public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
            //Log.d(TAG, consoleMessage.message() + " -- From line "
            //        + consoleMessage.lineNumber() + " of "
            //        + consoleMessage.sourceId());
            return super.onConsoleMessage(consoleMessage);
        }

        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            super.onProgressChanged(view, newProgress);
            //Log.d(TAG, "loading progress: " + newProgress);
        }
    }

}
