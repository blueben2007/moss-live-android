package cn.moss.live.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/** 枚举以名字存 TEXT。 */
class Converters {
    @androidx.room.TypeConverter
    fun sessionTypeToString(value: SessionType): String = value.name

    @androidx.room.TypeConverter
    fun stringToSessionType(value: String): SessionType = SessionType.valueOf(value)

    @androidx.room.TypeConverter
    fun roleToString(value: Role): String = value.name

    @androidx.room.TypeConverter
    fun stringToRole(value: String): Role = Role.valueOf(value)
}

@Database(
    entities = [SessionEntity::class, MessageEntity::class, MessageFtsEntity::class],
    version = 1,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "mosslive.db",
                ).build().also { instance = it }
            }
    }
}

/** FTS 查询词的宽松构造：多词 OR + 前缀通配，非法引号一律剔除。 */
fun buildFtsQuery(raw: String): String =
    raw.trim()
        .replace("\"", "")
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }
        .joinToString(" OR ") { "$it*" }
