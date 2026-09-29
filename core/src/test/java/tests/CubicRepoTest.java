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

package tests;

import com.seibel.distanthorizons.api.enums.config.EDhApiDataCompressionMode;
import com.seibel.distanthorizons.api.enums.config.EDhApiWorldCompressionMode;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGenerationStep;
import com.seibel.distanthorizons.core.dataObjects.fullData.FullDataPointIdMap;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.enums.EDhDirection;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.sql.dto.FullDataSourceV2DTO;
import com.seibel.distanthorizons.core.sql.repo.CubicFullDataSourceV2Repo;
import com.seibel.distanthorizons.core.sql.repo.FullDataSourceV2Repo;
import com.seibel.distanthorizons.core.util.FullDataPointUtil;
import com.seibel.distanthorizons.core.util.objects.DataCorruptedException;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import testItems.wrappers.TestBiomeWrapper;
import testItems.wrappers.TestBlockStateWrapper;

import java.io.File;
import java.util.Arrays;
import java.util.Map;

/** Saving and loading cubic sections, see {@link CubicFullDataSourceV2Repo}. */
public class CubicRepoTest
{
	private static final String DATABASE_TYPE = "jdbc:sqlite";
	private static final String CUBIC_DB_FILE_NAME = "cubicTest.sqlite";
	private static final String FLAT_DB_FILE_NAME = "flatTest.sqlite";



	@BeforeClass
	public static void testSetup()
	{
		for (String name : new String[] { CUBIC_DB_FILE_NAME, FLAT_DB_FILE_NAME })
		{
			File dbFile = new File(name);
			if (dbFile.exists())
			{
				Assert.assertTrue("unable to delete old test DB File.", dbFile.delete());
			}
		}
	}



	@Test
	public void saveLoadAndAdjacentData() throws Exception
	{
		try (CubicFullDataSourceV2Repo repo = new CubicFullDataSourceV2Repo(DATABASE_TYPE, new File(CUBIC_DB_FILE_NAME)))
		{
			long pos = DhSectionPos.encodeCubic((byte) 6, 3, -5, -7);
			// another section in the same column, it must not collide with the first
			long abovePos = DhSectionPos.encodeCubic((byte) 6, 3, -4, -7);

			FullDataSourceV2 original = testSource(pos, 20);
			FullDataSourceV2 above = testSource(abovePos, 50);
			repo.save(FullDataSourceV2DTO.CreateFromDataSource(original, EDhApiDataCompressionMode.LZ4));
			repo.save(FullDataSourceV2DTO.CreateFromDataSource(above, EDhApiDataCompressionMode.LZ4));

			Assert.assertTrue(repo.existsWithKey(pos));
			Assert.assertTrue(repo.existsWithKey(abovePos));
			Assert.assertFalse(repo.existsWithKey(DhSectionPos.encodeCubic((byte) 6, 3, -3, -7)));

			// full round trip
			try (FullDataSourceV2DTO dto = repo.getByKey(pos);
				FullDataSourceV2 loaded = dto.createUnitTestDataSource())
			{
				Assert.assertEquals(pos, loaded.getPos());
				for (int i = 0; i < FullDataSourceV2.WIDTH * FullDataSourceV2.WIDTH; i++)
				{
					Assert.assertEquals("column " + i, original.dataPoints[i], loaded.dataPoints[i]);
				}
			}

			// the section above only needs our top data points, the section below our bottom ones
			try (FullDataSourceV2DTO upDto = repo.getAdjByPosAndDirection(pos, EDhDirection.UP);
				FullDataSourceV2 up = upDto.createUnitTestDataSource(EDhDirection.UP))
			{
				LongArrayList column = up.getColumnAtRelPos(10, 10);
				Assert.assertEquals(1, column.size());
				Assert.assertEquals(20, FullDataPointUtil.getBottomY(column.getLong(0)));
				Assert.assertEquals(64, FullDataPointUtil.getBottomY(column.getLong(0)) + FullDataPointUtil.getHeight(column.getLong(0)));
			}
			try (FullDataSourceV2DTO downDto = repo.getAdjByPosAndDirection(pos, EDhDirection.DOWN);
				FullDataSourceV2 down = downDto.createUnitTestDataSource(EDhDirection.DOWN))
			{
				LongArrayList column = down.getColumnAtRelPos(63, 0);
				Assert.assertEquals(1, column.size());
				Assert.assertEquals(0, FullDataPointUtil.getBottomY(column.getLong(0)));
				Assert.assertEquals(20, FullDataPointUtil.getHeight(column.getLong(0)));
			}
			// horizontal adjacency still works
			try (FullDataSourceV2DTO northDto = repo.getAdjByPosAndDirection(pos, EDhDirection.NORTH);
				FullDataSourceV2 north = northDto.createUnitTestDataSource(EDhDirection.NORTH))
			{
				Assert.assertEquals(original.getColumnAtRelPos(5, 0), north.getColumnAtRelPos(5, 0));
			}

			// flags and the propagation queries
			repo.setApplyToParent(pos, true);
			LongArrayList toUpdate = repo.getParentPositionsToUpdate(0, 0, 0, 10);
			Assert.assertEquals(1, toUpdate.size());
			Assert.assertEquals(pos, toUpdate.getLong(0));
			repo.setApplyToParent(pos, false);
			Assert.assertTrue(repo.getParentPositionsToUpdate(0, 0, 0, 10).isEmpty());

			// the nearer section (vertically) comes first
			repo.setApplyToParent(pos, true);
			repo.setApplyToParent(abovePos, true);
			toUpdate = repo.getParentPositionsToUpdate(3 * 64, -4 * 64, -7 * 64, 10);
			Assert.assertEquals(abovePos, toUpdate.getLong(0));
			toUpdate = repo.getParentPositionsToUpdate(3 * 64, -5 * 64, -7 * 64, 10);
			Assert.assertEquals(pos, toUpdate.getLong(0));

			// generation steps and timestamps
			it.unimi.dsi.fastutil.bytes.ByteArrayList steps = new it.unimi.dsi.fastutil.bytes.ByteArrayList();
			repo.getColumnGenerationStepForPos(pos, steps);
			Assert.assertEquals(FullDataSourceV2.WIDTH * FullDataSourceV2.WIDTH, steps.size());
			Assert.assertEquals(EDhApiWorldGenerationStep.FEATURES.value, steps.getByte(100));
			Assert.assertNotNull(repo.getTimestampForPos(pos));

			LongArrayList all = repo.getAllPositions();
			Assert.assertEquals(2, all.size());
			Assert.assertTrue(all.contains(pos) && all.contains(abovePos));

			repo.deleteWithKey(abovePos);
			Assert.assertFalse(repo.existsWithKey(abovePos));
			Assert.assertTrue(repo.existsWithKey(pos));
		}
	}

	@Test
	public void networkRoundTrip() throws Exception
	{
		long pos = DhSectionPos.encodeCubic((byte) 7, -1, 2, 1);
		FullDataSourceV2 original = testSource(pos, 90);
		FullDataSourceV2DTO dto = FullDataSourceV2DTO.CreateFromDataSource(original, EDhApiDataCompressionMode.LZ4);
		Assert.assertTrue(dto.compressedUpAdjDataByteArray.size() > 0);

		ByteBuf buffer = Unpooled.buffer();
		dto.encode(buffer);
		FullDataSourceV2DTO decoded = FullDataSourceV2DTO.CreateEmptyDataSourceForDecoding();
		decoded.decode(buffer);
		Assert.assertEquals(0, buffer.readableBytes());
		Assert.assertEquals(pos, decoded.pos);
		Assert.assertEquals(dto.compressedUpAdjDataByteArray, decoded.compressedUpAdjDataByteArray);
		Assert.assertEquals(dto.compressedDownAdjDataByteArray, decoded.compressedDownAdjDataByteArray);

		// 2D data goes over the wire without the vertical data
		long flatPos = DhSectionPos.encode((byte) 6, 0, 0);
		FullDataSourceV2DTO flatDto = FullDataSourceV2DTO.CreateFromDataSource(testSource(flatPos, 90, 384), EDhApiDataCompressionMode.LZ4);
		Assert.assertEquals(0, flatDto.compressedUpAdjDataByteArray.size());
		ByteBuf flatBuffer = Unpooled.buffer();
		flatDto.encode(flatBuffer);
		FullDataSourceV2DTO flatDecoded = FullDataSourceV2DTO.CreateEmptyDataSourceForDecoding();
		flatDecoded.decode(flatBuffer);
		Assert.assertEquals(0, flatBuffer.readableBytes());
	}

	@Test
	public void flatDatabasesAreUntouched() throws Exception
	{
		try (FullDataSourceV2Repo repo = new FullDataSourceV2Repo(DATABASE_TYPE, new File(FLAT_DB_FILE_NAME)))
		{
			Map<String, Object> result = repo.queryDictionaryFirst("SELECT COUNT(name) as 'tableCount' FROM sqlite_master WHERE type='table' AND name='" + CubicFullDataSourceV2Repo.TABLE_NAME + "';");
			Assert.assertEquals(0, ((Number) result.get("tableCount")).intValue());
		}
	}



	//================//
	// helper methods //
	//================//

	private static FullDataSourceV2 testSource(long pos, int stoneTop) throws DataCorruptedException
	{ return testSource(pos, stoneTop, DhSectionPos.getCubicBlockHeight(pos)); }
	/** stone below stoneTop and air above it, in every column */
	private static FullDataSourceV2 testSource(long pos, int stoneTop, int height) throws DataCorruptedException
	{
		FullDataPointIdMap mapping = new FullDataPointIdMap(pos);
		int stone = mapping.addIfNotPresentAndGetId(new TestBiomeWrapper("plains"), new TestBlockStateWrapper("stone"));
		int air = mapping.addIfNotPresentAndGetId(new TestBiomeWrapper("plains"), new TestBlockStateWrapper("air"));

		LongArrayList[] columns = new LongArrayList[FullDataSourceV2.WIDTH * FullDataSourceV2.WIDTH];
		for (int i = 0; i < columns.length; i++)
		{
			columns[i] = new LongArrayList();
			columns[i].add(FullDataPointUtil.encode(air, height - stoneTop, stoneTop, (byte) 0, (byte) 15));
			columns[i].add(FullDataPointUtil.encode(stone, stoneTop, 0, (byte) (i % 16), (byte) 0));
		}

		byte[] steps = new byte[columns.length];
		Arrays.fill(steps, EDhApiWorldGenerationStep.FEATURES.value);
		byte[] compression = new byte[columns.length];
		Arrays.fill(compression, EDhApiWorldCompressionMode.MERGE_SAME_BLOCKS.value);
		return FullDataSourceV2.createWithData(pos, mapping, columns, steps, compression);
	}

}
