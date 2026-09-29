/*
 *    This file is part of the Distant Horizons mod
 *    licensed under the GNU LGPL v3 License.
 *
 *    Copyright (C) 2020 James Seibel
 *
 *    This program is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU Lesser General Public License as published by
 *    the Free Software Foundation, version 3.
 *
 *    This program is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU Lesser General Public License for more details.
 *
 *    You should have received a copy of the GNU Lesser General Public License
 *    along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.seibel.distanthorizons.core.sql.repo;

import com.seibel.distanthorizons.core.enums.EDhDirection;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.sql.dto.FullDataSourceV2DTO;
import com.seibel.distanthorizons.core.util.BoolUtil;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Stores cubic {@link com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2}s
 * (see {@link DhSectionPos#isCubic(long)}) in a table of their own, "FullDataCubic", keyed by
 * detail level and X, Y and Z. <br><br>
 *
 * The table is created by this repo, not by a migration script, so databases of 2D levels
 * are left exactly as an unmodified Distant Horizons writes them. It lives in the level's
 * normal database file next to the other tables.
 */
public class CubicFullDataSourceV2Repo extends FullDataSourceV2Repo
{
	public static final String TABLE_NAME = "FullDataCubic";



	//=============//
	// constructor //
	//=============//
	//region

	public CubicFullDataSourceV2Repo(String databaseType, File databaseFile) throws SQLException, IOException
	{
		super(databaseType, databaseFile);
		this.createTableIfMissing();
	}

	private void createTableIfMissing()
	{
		this.queryDictionaryFirst(
			"CREATE TABLE IF NOT EXISTS " + TABLE_NAME + " ( \n" +
			"    DetailLevel TINYINT NOT NULL -- LOD detail level, not section detail level IE 0, 1, 2 not 6, 7, 8 \n" +
			"   ,PosX INT NOT NULL \n" +
			"   ,PosY INT NOT NULL \n" +
			"   ,PosZ INT NOT NULL \n" +
			"   \n" +
			"   ,DataChecksum INT NOT NULL \n" +
			"   \n" +
			"   ,Data BLOB NULL \n" +
			"   ,ColumnGenerationStep BLOB NULL \n" +
			"   ,ColumnWorldCompressionMode BLOB NULL \n" +
			"   ,Mapping BLOB NULL \n" +
			"   \n" +
			"   ,NorthAdjData BLOB NULL \n" +
			"   ,SouthAdjData BLOB NULL \n" +
			"   ,EastAdjData BLOB NULL \n" +
			"   ,WestAdjData BLOB NULL \n" +
			"   ,UpAdjData BLOB NULL \n" +
			"   ,DownAdjData BLOB NULL \n" +
			"   \n" +
			"   ,DataFormatVersion TINYINT NULL \n" +
			"   ,CompressionMode TINYINT NULL \n" +
			"   \n" +
			"   ,ApplyToParent BIT NULL \n" +
			"   ,ApplyToChildren BIT NULL \n" +
			"   ,Regenerate BIT NULL \n" +
			"   \n" +
			"   ,LastModifiedUnixDateTime BIGINT NOT NULL -- in GMT 0 \n" +
			"   ,CreatedUnixDateTime BIGINT NOT NULL -- in GMT 0 \n" +
			"   \n" +
			"   ,PRIMARY KEY (DetailLevel, PosX, PosY, PosZ) \n" +
			");");
		this.queryDictionaryFirst("CREATE INDEX IF NOT EXISTS FullDataCubicApplyToParentIndex ON " + TABLE_NAME + " (ApplyToParent) WHERE ApplyToParent = 1;");
		this.queryDictionaryFirst("CREATE INDEX IF NOT EXISTS FullDataCubicApplyToChildrenIndex ON " + TABLE_NAME + " (ApplyToChildren) WHERE ApplyToChildren = 1;");
		this.queryDictionaryFirst("CREATE INDEX IF NOT EXISTS FullDataCubicRegenerateIndex ON " + TABLE_NAME + " (Regenerate) WHERE Regenerate = 1;");
	}

	//endregion



	//===========//
	// overrides //
	//===========//
	//region

	@Override
	public String getTableName() { return TABLE_NAME; }

	@Override
	protected String CreateParameterizedWhereString() { return "DetailLevel = ? AND PosX = ? AND PosY = ? AND PosZ = ?"; }

	@Override
	protected int setPreparedStatementWhereClause(PreparedStatement statement, int index, Long pos) throws SQLException
	{
		assertCubic(pos);

		statement.setInt(index++, DhSectionPos.getDetailLevel(pos) - DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL);
		statement.setInt(index++, DhSectionPos.getX(pos));
		statement.setInt(index++, DhSectionPos.getY(pos));
		statement.setInt(index++, DhSectionPos.getZ(pos));

		return index;
	}

	@Override
	protected String getPositionColumnsSql() { return "DetailLevel, PosX, PosY, PosZ"; }

	/**
	 * Manhattan distance between the section's min corner and the target,
	 * sections are {@link DhSectionPos#CUBIC_MAX_VERTICAL_DETAIL_LEVEL} tall at most.
	 */
	@Override
	protected String getDistanceSql()
	{
		return "abs((PosX << (6 + DetailLevel)) - ?) " +
			"+ abs((PosZ << (6 + DetailLevel)) - ?) " +
			"+ abs((PosY << min(6 + DetailLevel, " + DhSectionPos.CUBIC_MAX_VERTICAL_DETAIL_LEVEL + ")) - ?)";
	}
	@Override
	protected int setDistanceParameters(PreparedStatement statement, int index, int targetBlockPosX, int targetBlockPosY, int targetBlockPosZ) throws SQLException
	{
		statement.setInt(index++, targetBlockPosX);
		statement.setInt(index++, targetBlockPosZ);
		statement.setInt(index++, targetBlockPosY);
		return index;
	}

	@Override
	protected long readPosFromResultSet(ResultSet resultSet) throws SQLException
	{
		byte detailLevel = resultSet.getByte("DetailLevel");
		byte sectionDetailLevel = (byte) (detailLevel + DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL);
		return DhSectionPos.encodeCubic(sectionDetailLevel, resultSet.getInt("PosX"), resultSet.getInt("PosY"), resultSet.getInt("PosZ"));
	}

	@Override
	protected void readAdditionalAdjacentData(ResultSet resultSet, FullDataSourceV2DTO dto) throws SQLException, IOException
	{
		dto.compressedUpAdjDataByteArray = putAllBytes(resultSet.getBinaryStream("UpAdjData"), dto.compressedUpAdjDataByteArray);
		dto.compressedDownAdjDataByteArray = putAllBytes(resultSet.getBinaryStream("DownAdjData"), dto.compressedDownAdjDataByteArray);
	}

	@Override
	protected String getAdjSqlForDirection(EDhDirection direction) throws IllegalArgumentException
	{
		switch (direction)
		{
			case UP:
				return this.getAdjForDirectionSqlTemplate().replace("DIRECTION_ENUM", "UpAdjData");
			case DOWN:
				return this.getAdjForDirectionSqlTemplate().replace("DIRECTION_ENUM", "DownAdjData");
			default:
				return super.getAdjSqlForDirection(direction);
		}
	}

	@Override
	public PreparedStatement createUpsertStatement(FullDataSourceV2DTO dto) throws SQLException
	{
		assertCubic(dto.pos);

		// see the 2D version for why this string is dynamic
		String upsertSqlTemplate = (
			"INSERT INTO " + this.getTableName() + " (\n" +
			"   DetailLevel, PosX, PosY, PosZ, \n" +
			"   DataChecksum, \n" +
			"   Data, ColumnGenerationStep, ColumnWorldCompressionMode, Mapping, \n" +
			"   NorthAdjData, SouthAdjData, EastAdjData, WestAdjData, UpAdjData, DownAdjData, \n" +
			"   DataFormatVersion, CompressionMode, ApplyToParent, ApplyToChildren, Regenerate, \n" +
			"   LastModifiedUnixDateTime, CreatedUnixDateTime) \n" +
			"VALUES( \n" +
			"    ?, ?, ?, ?, \n" +
			"    ?, \n" +
			"    ?, ?, ?, ?, \n" +
			"    ?, ?, ?, ?, ?, ?, \n" +
			"    ?, ?, ?, ?, ?, \n" +
			"    ?, ? \n" +
			") \n" +
			"ON CONFLICT(DetailLevel, PosX, PosY, PosZ) DO UPDATE SET \n" +
			"    DataChecksum = excluded.DataChecksum \n" +

			"   ,Data = excluded.Data \n" +
			"   ,ColumnGenerationStep = excluded.ColumnGenerationStep \n" +
			"   ,ColumnWorldCompressionMode = excluded.ColumnWorldCompressionMode \n" +
			"   ,Mapping = excluded.Mapping \n" +
			"   ,NorthAdjData = excluded.NorthAdjData, SouthAdjData = excluded.SouthAdjData \n" +
			"   ,EastAdjData = excluded.EastAdjData, WestAdjData = excluded.WestAdjData \n" +
			"   ,UpAdjData = excluded.UpAdjData, DownAdjData = excluded.DownAdjData \n" +

			"   ,DataFormatVersion = excluded.DataFormatVersion \n" +
			"   ,CompressionMode = excluded.CompressionMode \n" +
			// only update these values if they're present
			(dto.applyToParent != null ? "   ,ApplyToParent = excluded.ApplyToParent \n" : "") +
			(dto.applyToChildren != null ? "   ,ApplyToChildren = excluded.ApplyToChildren \n" : "") +
			(dto.regenerate != null ? "   ,Regenerate = excluded.Regenerate \n" : "") +

			"   ,LastModifiedUnixDateTime = excluded.LastModifiedUnixDateTime \n"
		).intern();


		PreparedStatement statement = this.createPreparedStatement(upsertSqlTemplate);
		if (statement == null)
		{
			return null;
		}


		int i = 1;
		i = this.setPreparedStatementWhereClause(statement, i, dto.pos);

		statement.setInt(i++, dto.dataChecksum);

		statement.setBinaryStream(i++, new ByteArrayInputStream(dto.compressedDataByteArray.elements()), dto.compressedDataByteArray.size());
		statement.setBinaryStream(i++, new ByteArrayInputStream(dto.compressedColumnGenStepByteArray.elements()), dto.compressedColumnGenStepByteArray.size());
		statement.setBinaryStream(i++, new ByteArrayInputStream(dto.compressedWorldCompressionModeByteArray.elements()), dto.compressedWorldCompressionModeByteArray.size());
		statement.setBinaryStream(i++, new ByteArrayInputStream(dto.compressedMappingByteArray.elements()), dto.compressedMappingByteArray.size());
		// adjacent full data
		statement.setBinaryStream(i++, new ByteArrayInputStream(dto.compressedNorthAdjDataByteArray.elements()), dto.compressedNorthAdjDataByteArray.size());
		statement.setBinaryStream(i++, new ByteArrayInputStream(dto.compressedSouthAdjDataByteArray.elements()), dto.compressedSouthAdjDataByteArray.size());
		statement.setBinaryStream(i++, new ByteArrayInputStream(dto.compressedEastAdjDataByteArray.elements()), dto.compressedEastAdjDataByteArray.size());
		statement.setBinaryStream(i++, new ByteArrayInputStream(dto.compressedWestAdjDataByteArray.elements()), dto.compressedWestAdjDataByteArray.size());
		statement.setBinaryStream(i++, new ByteArrayInputStream(dto.compressedUpAdjDataByteArray.elements()), dto.compressedUpAdjDataByteArray.size());
		statement.setBinaryStream(i++, new ByteArrayInputStream(dto.compressedDownAdjDataByteArray.elements()), dto.compressedDownAdjDataByteArray.size());

		statement.setByte(i++, dto.dataFormatVersion);
		statement.setByte(i++, dto.compressionModeValue);
		// if nothing is present assume we don't need/want to propagate updates
		statement.setBoolean(i++, BoolUtil.falseIfNull(dto.applyToParent));
		statement.setBoolean(i++, BoolUtil.falseIfNull(dto.applyToChildren));
		statement.setBoolean(i++, BoolUtil.falseIfNull(dto.regenerate));

		statement.setLong(i++, System.currentTimeMillis()); // last modified unix time
		statement.setLong(i++, System.currentTimeMillis()); // created unix time (only used if this is a fresh insert)

		return statement;
	}

	//endregion



	//================//
	// helper methods //
	//================//
	//region

	private static void assertCubic(long pos)
	{
		if (!DhSectionPos.isCubic(pos))
		{
			throw new IllegalArgumentException("The cubic repo can only store cubic positions, given [" + DhSectionPos.toString(pos) + "].");
		}
	}

	//endregion



}
