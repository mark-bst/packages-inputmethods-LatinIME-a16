/*
 * Copyright (C) 2026 BlueStacks
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.inputmethod.latin;

import android.content.Context;
import android.util.Log;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import com.android.inputmethod.latin.utils.InputTypeUtils;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** BlueStacks host-input transport and physical-key compatibility hooks. */
final class BstImeBridge {
    private static final String TAG = "BstImeBridge";
    private static final String LISTENER_PORT_PROPERTY = "bst.config.ime_listenerport";
    private static final String COMPOSING_PROPERTY = "bst.ime_is_composing";
    private static final int MAX_COMMAND_LENGTH = 64 * 1024;
    private static final int MAX_DELETE_COUNT = 4096;
    private static final long COMMAND_TIMEOUT_SECONDS = 5;
    private static final Pattern OPTION_PATTERN =
            Pattern.compile("(?:^|\\s)(dl|en|cp)=(\\d+)(?=\\s|$)");

    private final LatinIME mIme;
    private final Object mBstUtils;
    private final Object mBstFilterApps;
    private volatile boolean mRunning;
    private volatile ServerSocket mServerSocket;
    private Thread mListenerThread;
    private String mPreviousPackageName;
    private boolean mSoftKeyboardRequired;
    private String mSoftKeyboardModifier = "soft";

    BstImeBridge(LatinIME ime) {
        mIme = ime;
        final Context context = ime.getApplicationContext();
        mBstUtils = context.getSystemService("bstutils");
        mBstFilterApps = context.getSystemService("bstfilterapps");
    }

    void start() {
        if (mRunning || mBstUtils == null) {
            return;
        }
        mRunning = true;
        mListenerThread = new Thread(this::listen, "BstImeListener");
        mListenerThread.setDaemon(true);
        mListenerThread.start();
    }

    void stop() {
        mRunning = false;
        setProperty(LISTENER_PORT_PROPERTY, "0");
        final ServerSocket serverSocket = mServerSocket;
        mServerSocket = null;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                Log.w(TAG, "Failed to close IME listener", e);
            }
        }
    }

    boolean handleHardwareKey(int keyCode, KeyEvent event) {
        final InputConnection connection = mIme.getCurrentInputConnection();
        if (connection == null) {
            return false;
        }

        if (keyCode == KeyEvent.KEYCODE_ENTER) {
            if (!performEditorAction(connection)) {
                sendKey(connection, keyCode);
            }
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DEL || keyCode == KeyEvent.KEYCODE_FORWARD_DEL) {
            sendKey(connection, keyCode);
            return true;
        }
        if (event.isCtrlPressed() || event.isMetaPressed()) {
            return false;
        }

        final int unicodeCodePoint = event.getUnicodeChar(event.getMetaState());
        if (unicodeCodePoint == 0 || !isHardTextInjectionRequired()) {
            return false;
        }
        connection.commitText(new String(Character.toChars(unicodeCodePoint)), 1);
        return true;
    }

    private boolean isHardTextInjectionRequired() {
        if (mBstFilterApps == null) {
            return false;
        }
        final String packageName = getSystemProperty("bst.config.top_package_name", "");
        final String activityName = getSystemProperty("bst.config.top_activity_name", "");
        if (!packageName.equals(mPreviousPackageName)) {
            mSoftKeyboardRequired = invokeBoolean(mBstFilterApps,
                    "isSoftKeyboardRequired", packageName, activityName);
            mSoftKeyboardModifier = mSoftKeyboardRequired
                    ? invokeString(mBstFilterApps, "getSoftKeyboardModifier", packageName)
                    : "soft";
            mPreviousPackageName = packageName;
        }
        return mSoftKeyboardRequired
                && ("hard".equals(mSoftKeyboardModifier)
                || "pass".equals(mSoftKeyboardModifier));
    }

    private void listen() {
        while (mRunning) {
            try (ServerSocket serverSocket = new ServerSocket(
                    0, 4, InetAddress.getLoopbackAddress())) {
                serverSocket.setReuseAddress(true);
                mServerSocket = serverSocket;
                setProperty(LISTENER_PORT_PROPERTY,
                        Integer.toString(serverSocket.getLocalPort()));
                while (mRunning) {
                    try (Socket socket = serverSocket.accept()) {
                        handleClient(socket);
                    }
                }
            } catch (IOException e) {
                if (mRunning) {
                    Log.e(TAG, "IME listener failed; retrying", e);
                    android.os.SystemClock.sleep(1000);
                }
            } finally {
                mServerSocket = null;
                setProperty(LISTENER_PORT_PROPERTY, "0");
            }
        }
    }

    private void handleClient(Socket socket) throws IOException {
        if (!socket.getInetAddress().isLoopbackAddress()) {
            Log.w(TAG, "Rejecting non-loopback IME client: " + socket.getInetAddress());
            return;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                     socket.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter writer = new PrintWriter(new BufferedWriter(new OutputStreamWriter(
                     socket.getOutputStream(), StandardCharsets.UTF_8)), true)) {
            String command;
            while (mRunning && (command = reader.readLine()) != null) {
                if (command.length() > MAX_COMMAND_LENGTH) {
                    Log.w(TAG, "Rejecting oversized IME command");
                    writer.println("error");
                    return;
                }
                writer.println(dispatchCommand(command) == 0 ? "ok" : "error");
            }
        }
    }

    private int dispatchCommand(String command) {
        final FutureTask<Integer> task = new FutureTask<>(() -> processCommand(command));
        mIme.getMainExecutor().execute(task);
        try {
            return task.get(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            task.cancel(false);
            Log.e(TAG, "IME command failed", e);
            return -1;
        }
    }

    private int processCommand(String command) {
        final int textStart = command.indexOf("s_");
        final int textEnd = command.lastIndexOf("_e");
        if (textStart < 0 || textEnd < textStart + 2) {
            return -1;
        }
        final String text = command.substring(textStart + 2, textEnd);
        int deleteCount = 0;
        boolean sendEnter = false;
        boolean composing = false;
        final Matcher matcher = OPTION_PATTERN.matcher(command.substring(textEnd + 2));
        while (matcher.find()) {
            final int value;
            try {
                value = Integer.parseInt(matcher.group(2));
            } catch (NumberFormatException e) {
                return -1;
            }
            switch (matcher.group(1)) {
                case "dl":
                    deleteCount = Math.min(value, MAX_DELETE_COUNT);
                    break;
                case "en":
                    sendEnter = value > 0;
                    break;
                case "cp":
                    composing = value > 0;
                    break;
                default:
                    break;
            }
        }

        final InputConnection connection = mIme.getCurrentInputConnection();
        if (connection == null) {
            return -1;
        }
        setProperty(COMPOSING_PROPERTY, composing ? "1" : "0");
        connection.beginBatchEdit();
        try {
            if (deleteCount > 0 && !connection.deleteSurroundingText(deleteCount, 0)) {
                for (int i = 0; i < deleteCount; i++) {
                    sendKey(connection, KeyEvent.KEYCODE_DEL);
                }
            }
            if (!text.isEmpty()) {
                if (composing) {
                    connection.setComposingText(text, 1);
                } else {
                    connection.commitText(text, 1);
                }
            }
            if (sendEnter && !performEditorAction(connection)) {
                sendKey(connection, KeyEvent.KEYCODE_ENTER);
            }
        } finally {
            connection.endBatchEdit();
        }
        return 0;
    }

    private boolean performEditorAction(InputConnection connection) {
        final EditorInfo editorInfo = mIme.getCurrentInputEditorInfo();
        if (editorInfo == null) {
            return false;
        }
        final int action = InputTypeUtils.getImeOptionsActionIdFromEditorInfo(editorInfo);
        if (action == InputTypeUtils.IME_ACTION_CUSTOM_LABEL) {
            return connection.performEditorAction(editorInfo.actionId);
        }
        return action != EditorInfo.IME_ACTION_NONE && connection.performEditorAction(action);
    }

    private static void sendKey(InputConnection connection, int keyCode) {
        final long eventTime = android.os.SystemClock.uptimeMillis();
        connection.sendKeyEvent(new KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN,
                keyCode, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                KeyEvent.FLAG_SOFT_KEYBOARD | KeyEvent.FLAG_KEEP_TOUCH_MODE));
        connection.sendKeyEvent(new KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP,
                keyCode, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                KeyEvent.FLAG_SOFT_KEYBOARD | KeyEvent.FLAG_KEEP_TOUCH_MODE));
    }

    private void setProperty(String key, String value) {
        if (mBstUtils == null) {
            return;
        }
        try {
            mBstUtils.getClass().getMethod(
                    "setProperty", String.class, String.class).invoke(mBstUtils, key, value);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w(TAG, "Unable to set " + key, e);
        }
    }

    private static String getSystemProperty(String key, String fallback) {
        try {
            final Class<?> systemProperties = Class.forName("android.os.SystemProperties");
            return (String) systemProperties.getMethod(
                    "get", String.class, String.class).invoke(null, key, fallback);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return fallback;
        }
    }

    private static boolean invokeBoolean(Object target, String method, String first,
            String second) {
        try {
            return (Boolean) target.getClass().getMethod(method, String.class, String.class)
                    .invoke(target, first, second);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w(TAG, "Unable to call " + method, e);
            return false;
        }
    }

    private static String invokeString(Object target, String method, String argument) {
        try {
            final Object result = target.getClass().getMethod(method, String.class)
                    .invoke(target, argument);
            return result instanceof String ? (String) result : "soft";
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w(TAG, "Unable to call " + method, e);
            return "soft";
        }
    }
}
