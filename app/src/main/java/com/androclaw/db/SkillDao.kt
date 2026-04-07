package com.androclaw.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface SkillDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(skill: SkillEntity): Long

    @Update
    suspend fun update(skill: SkillEntity)

    @Query("SELECT * FROM skills WHERE id = :id")
    suspend fun getById(id: Long): SkillEntity?

    @Query("SELECT * FROM skills WHERE trigger = :trigger LIMIT 1")
    suspend fun getByTrigger(trigger: String): SkillEntity?

    @Query("SELECT * FROM skills ORDER BY category ASC, name ASC")
    suspend fun getAll(): List<SkillEntity>

    @Query("SELECT * FROM skills WHERE category = :category ORDER BY name ASC")
    suspend fun getByCategory(category: String): List<SkillEntity>

    @Query("SELECT * FROM skills WHERE name LIKE '%' || :query || '%' OR `trigger` LIKE '%' || :query || '%' OR description LIKE '%' || :query || '%'")
    suspend fun search(query: String): List<SkillEntity>

    @Query("DELETE FROM skills WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM skills WHERE isBuiltIn = 1")
    suspend fun deleteAllBuiltIn()

    @Query("SELECT COUNT(*) FROM skills")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM skills WHERE isBuiltIn = 1")
    suspend fun countBuiltIn(): Int
}
