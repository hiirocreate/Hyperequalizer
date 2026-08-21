package jp.hyperequalizer.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [MediaStateEntity::class, PlaylistEntity::class, PlaylistItemEntity::class, HiddenFolderEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun mediaStateDao(): MediaStateDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun hiddenFolderDao(): HiddenFolderDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        /** v1→v2: 「非表示」機能(ファイル単位のisHiddenカラム + フォルダ単位のhidden_folderテーブル)を追加 */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media_state ADD COLUMN isHidden INTEGER NOT NULL DEFAULT 0")
                // hidden_folderは新規テーブルなので、既存行への穴埋めが必要なALTER TABLEとは違い
                // DEFAULT句は必須ではない。Roomがコンパイル時に期待するスキーマ(hiddenAtに
                // @ColumnInfoのdefaultValueを付けていないためSQL上のDEFAULT無し)と一致させるため、
                // あえてDEFAULT句を付けていない(付けると起動時のスキーマ検証で不一致になる)。
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS hidden_folder (" +
                        "folderPath TEXT NOT NULL, " +
                        "mediaType TEXT NOT NULL, " +
                        "hiddenAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(folderPath, mediaType))"
                )
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "hypereq.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    // 万一想定外のバージョン間移動が起きた場合の保険(通常はMIGRATION_1_2が使われる)
                    .fallbackToDestructiveMigration()
                    .build().also { instance = it }
            }
        }
    }
}
