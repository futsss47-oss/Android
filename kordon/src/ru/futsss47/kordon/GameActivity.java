package ru.futsss47.kordon;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public final class GameActivity extends Activity {
    private WebView game;

    private void fullscreen() {
        getWindow().getDecorView().setSystemUiVisibility(5894);
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        fullscreen();
        game = new WebView(this);
        game.setBackgroundColor(0xff19251f);
        game.getSettings().setJavaScriptEnabled(true);
        game.getSettings().setDomStorageEnabled(true);
        game.getSettings().setAllowFileAccessFromFileURLs(false);
        game.getSettings().setAllowUniversalAccessFromFileURLs(false);
        game.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return true;
            }
        });
        setContentView(game);
        game.loadUrl("file:///android_asset/index.html");
    }
    @Override public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) fullscreen();
    }
    @Override protected void onPause() {
        game.evaluateJavascript("window.suspendGame && window.suspendGame()", null);
        game.onPause();
        super.onPause();
    }
    @Override protected void onResume() {
        super.onResume();
        if (game != null) game.onResume();
    }
    @Override public void onBackPressed() {
        game.evaluateJavascript("window.toggleMenu && window.toggleMenu()", null);
    }
}
