package com.tjg.twidget.social

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

internal class SocialDatabase(context: Context, name: String) : SQLiteOpenHelper(context, name, null, 1) {
    override fun onConfigure(db: SQLiteDatabase) = db.setForeignKeyConstraintsEnabled(true)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE accounts (
            id TEXT PRIMARY KEY NOT NULL, platform TEXT NOT NULL, remote_id TEXT,
            handle TEXT NOT NULL, display_name TEXT NOT NULL, avatar_url TEXT NOT NULL,
            UNIQUE(platform, remote_id))""")
        db.execSQL("""CREATE TABLE profiles (
            id TEXT PRIMARY KEY NOT NULL, name_source TEXT NOT NULL, avatar_source TEXT NOT NULL,
            custom_name TEXT, membership_version INTEGER NOT NULL, position INTEGER NOT NULL)""")
        db.execSQL("""CREATE TABLE members (
            account_id TEXT PRIMARY KEY NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
            profile_id TEXT NOT NULL REFERENCES profiles(id) ON DELETE CASCADE, position INTEGER NOT NULL)""")
        db.execSQL("""CREATE TABLE widget_bindings (
            widget_id INTEGER PRIMARY KEY, account_id TEXT REFERENCES accounts(id) ON DELETE SET NULL,
            follows_default INTEGER NOT NULL)""")
        db.execSQL("""CREATE TABLE observations (
            account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
            metric TEXT NOT NULL, observed_at INTEGER NOT NULL, source TEXT NOT NULL,
            value INTEGER CHECK(value IS NULL OR value >= 0), precision TEXT NOT NULL,
            estimated INTEGER NOT NULL, imported INTEGER NOT NULL, shared_import INTEGER NOT NULL,
            PRIMARY KEY(account_id, metric, observed_at, source))""")
        db.execSQL("CREATE TABLE metadata (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("No migration from social schema $oldVersion to $newVersion")
    }
}
