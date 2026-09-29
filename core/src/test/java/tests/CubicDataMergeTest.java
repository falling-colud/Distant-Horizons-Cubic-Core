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

import com.seibel.distanthorizons.api.enums.config.EDhApiWorldCompressionMode;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGenerationStep;
import com.seibel.distanthorizons.core.dataObjects.fullData.FullDataPointIdMap;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.util.FullDataPointUtil;
import com.seibel.distanthorizons.core.util.objects.DataCorruptedException;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import org.junit.Assert;
import org.junit.Test;
import testItems.wrappers.TestBiomeWrapper;
import testItems.wrappers.TestBlockStateWrapper;

import java.util.Arrays;

/** Merging cubic children into cubic parents, see {@link FullDataSourceV2}'s cubic merges. */
public class CubicDataMergeTest
{
	private static final TestBiomeWrapper PLAINS = new TestBiomeWrapper("plains");
	private static final TestBlockStateWrapper STONE = new TestBlockStateWrapper("stone");
	private static final TestBlockStateWrapper AIR = new TestBlockStateWrapper("air");



	//=================//
	// column helpers  //
	//=================//

	@Test
	public void clipOffsetConcat() throws DataCorruptedException
	{
		// top down: air [40,64), stone [0,40)
		LongArrayList column = column(point(1, 40, 64, 15), point(0, 0, 40, 0));

		LongArrayList clipped = FullDataSourceV2.clipColumn(column, 30, 50, -30);
		Assert.assertEquals(column(point(1, 10, 20, 15), point(0, 0, 10, 0)), clipped);

		Assert.assertTrue(FullDataSourceV2.clipColumn(column, 64, 128, 0).isEmpty());

		LongArrayList offset = FullDataSourceV2.offsetColumn(column, 64);
		Assert.assertEquals(column(point(1, 104, 128, 15), point(0, 64, 104, 0)), offset);

		// the touching air points at the seam become one
		LongArrayList upper = column(point(1, 64, 128, 15));
		LongArrayList combined = FullDataSourceV2.concatColumns(upper, column);
		Assert.assertEquals(column(point(1, 40, 128, 15), point(0, 0, 40, 0)), combined);

		// different light: no merge
		LongArrayList darkUpper = column(point(1, 64, 128, 7));
		Assert.assertEquals(3, FullDataSourceV2.concatColumns(darkUpper, column).size());
	}



	//===================//
	// child into parent //
	//===================//

	@Test
	public void lowerThenUpperChild() throws DataCorruptedException
	{
		long parentPos = DhSectionPos.encodeCubic((byte) 7, 0, 0, 0);
		FullDataSourceV2 parent = FullDataSourceV2.createEmpty(parentPos);

		// lower child: stone up to 40, air above (child relative)
		FullDataSourceV2 lower = uniformChild(DhSectionPos.encodeCubic((byte) 6, 0, 0, 0), 40, EDhApiWorldGenerationStep.FEATURES);
		Assert.assertTrue(parent.updateFromDataSource(lower));

		LongArrayList mergedColumn = parent.getColumnAtRelPos(0, 0);
		assertColumn(mergedColumn, new int[][] { {40, 64}, {0, 40} });
		Assert.assertEquals(FullDataSourceV2.CUBIC_PARTIAL_GENERATION_STEP, parent.columnGenerationSteps.getByte(FullDataSourceV2.relativePosToIndex(0, 0)));
		// the child only covers the (0,0) quadrant
		Assert.assertTrue(parent.getColumnAtRelPos(40, 40).isEmpty());

		// upper child: stone up to 10, air above (child relative) -> parent Y 64..74 stone
		FullDataSourceV2 upper = uniformChild(DhSectionPos.encodeCubic((byte) 6, 0, 1, 0), 10, EDhApiWorldGenerationStep.FEATURES);
		Assert.assertTrue(parent.updateFromDataSource(upper));

		mergedColumn = parent.getColumnAtRelPos(0, 0);
		assertColumn(mergedColumn, new int[][] { {74, 128}, {64, 74}, {40, 64}, {0, 40} });
		byte step = parent.columnGenerationSteps.getByte(FullDataSourceV2.relativePosToIndex(0, 0));
		Assert.assertTrue("a column with both halves is complete, step " + step, FullDataSourceV2.isCompleteCubicGenerationStep(step));

		// re-merging the lower child replaces only the lower half
		FullDataSourceV2 newLower = uniformChild(DhSectionPos.encodeCubic((byte) 6, 0, 0, 0), 20, EDhApiWorldGenerationStep.LIGHT);
		Assert.assertTrue(parent.updateFromDataSource(newLower));
		assertColumn(parent.getColumnAtRelPos(0, 0), new int[][] { {74, 128}, {64, 74}, {20, 64}, {0, 20} });
	}

	@Test
	public void incompleteChildIsNotMerged() throws DataCorruptedException
	{
		long parentPos = DhSectionPos.encodeCubic((byte) 7, 0, 0, 0);
		FullDataSourceV2 parent = FullDataSourceV2.createEmpty(parentPos);
		FullDataSourceV2 lower = uniformChild(DhSectionPos.encodeCubic((byte) 6, 0, 0, 0), 40, EDhApiWorldGenerationStep.FEATURES);
		Assert.assertTrue(parent.updateFromDataSource(lower));

		// a partial child (as a cubic parent with one half would be) doesn't overwrite anything
		FullDataSourceV2 partial = uniformChild(DhSectionPos.encodeCubic((byte) 6, 0, 0, 0), 5, EDhApiWorldGenerationStep.FEATURES);
		Arrays.fill(partial.columnGenerationSteps.elements(), 0, FullDataSourceV2.WIDTH * FullDataSourceV2.WIDTH, FullDataSourceV2.CUBIC_PARTIAL_GENERATION_STEP);
		parent.updateFromDataSource(partial);
		assertColumn(parent.getColumnAtRelPos(0, 0), new int[][] { {40, 64}, {0, 40} });
	}

	@Test
	public void quadrantsAndNegativePositions() throws DataCorruptedException
	{
		// parent at y = -1 covers blocks [-128, 0); child y = -1 is its upper half
		long parentPos = DhSectionPos.encodeCubic((byte) 7, -1, -1, -1);
		FullDataSourceV2 parent = FullDataSourceV2.createEmpty(parentPos);
		long childPos = DhSectionPos.encodeCubic((byte) 6, -1, -1, -2);
		Assert.assertEquals(parentPos, DhSectionPos.getParentPos(childPos));

		FullDataSourceV2 child = uniformChild(childPos, 30, EDhApiWorldGenerationStep.FEATURES);
		Assert.assertTrue(parent.updateFromDataSource(child));

		// x = -1 is the upper x half of parent x = -1, z = -2 the lower z half
		Assert.assertTrue(parent.getColumnAtRelPos(0, 0).isEmpty());
		assertColumn(parent.getColumnAtRelPos(32, 0), new int[][] { {94, 128}, {64, 94} });
	}

	@Test
	public void slabChildCoversWholeColumn() throws DataCorruptedException
	{
		// detail 12 slabs split into 4 detail 11 children of the same height
		long parentPos = DhSectionPos.encodeCubic((byte) 12, 0, 0, 0);
		FullDataSourceV2 parent = FullDataSourceV2.createEmpty(parentPos);
		FullDataSourceV2 child = uniformChild(DhSectionPos.encodeCubic((byte) 11, 0, 0, 0), 700, EDhApiWorldGenerationStep.SURFACE);
		Assert.assertTrue(parent.updateFromDataSource(child));
		assertColumn(parent.getColumnAtRelPos(0, 0), new int[][] { {700, 2048}, {0, 700} });
		Assert.assertEquals(EDhApiWorldGenerationStep.SURFACE.value, parent.columnGenerationSteps.getByte(FullDataSourceV2.relativePosToIndex(0, 0)));
	}

	@Test
	public void downsampleTakesTheChildsHalf() throws DataCorruptedException
	{
		long parentPos = DhSectionPos.encodeCubic((byte) 7, 0, 0, 0);
		FullDataSourceV2 parent = FullDataSourceV2.createEmpty(parentPos);
		parent.updateFromDataSource(uniformChild(DhSectionPos.encodeCubic((byte) 6, 0, 0, 0), 40, EDhApiWorldGenerationStep.FEATURES));
		parent.updateFromDataSource(uniformChild(DhSectionPos.encodeCubic((byte) 6, 0, 1, 0), 10, EDhApiWorldGenerationStep.FEATURES));

		FullDataSourceV2 upperChild = FullDataSourceV2.createEmpty(DhSectionPos.encodeCubic((byte) 6, 0, 1, 0));
		Assert.assertTrue(upperChild.updateFromDataSource(parent));
		// parent columns are 2 blocks wide, child column 0 comes from parent column 0
		assertColumn(upperChild.getColumnAtRelPos(0, 0), new int[][] { {10, 64}, {0, 10} });
		Assert.assertEquals(EDhApiWorldGenerationStep.DOWN_SAMPLED.value, upperChild.columnGenerationSteps.getByte(0));
	}



	//================//
	// helper methods //
	//================//

	/** a cubic child section whose every column is stone below the given height and air above it */
	private static FullDataSourceV2 uniformChild(long pos, int stoneTop, EDhApiWorldGenerationStep step) throws DataCorruptedException
	{
		int height = DhSectionPos.getCubicBlockHeight(pos);
		FullDataPointIdMap mapping = new FullDataPointIdMap(pos);
		int stoneId = mapping.addIfNotPresentAndGetId(PLAINS, STONE);
		int airId = mapping.addIfNotPresentAndGetId(PLAINS, AIR);

		LongArrayList[] columns = new LongArrayList[FullDataSourceV2.WIDTH * FullDataSourceV2.WIDTH];
		for (int i = 0; i < columns.length; i++)
		{
			columns[i] = new LongArrayList();
			columns[i].add(FullDataPointUtil.encode(airId, height - stoneTop, stoneTop, (byte) 0, (byte) 15));
			columns[i].add(FullDataPointUtil.encode(stoneId, stoneTop, 0, (byte) 0, (byte) 0));
		}

		byte[] steps = new byte[columns.length];
		Arrays.fill(steps, step.value);
		byte[] compression = new byte[columns.length];
		Arrays.fill(compression, EDhApiWorldCompressionMode.MERGE_SAME_BLOCKS.value);
		return FullDataSourceV2.createWithData(pos, mapping, columns, steps, compression);
	}

	private static long point(int id, int bottomY, int topY, int skyLight) throws DataCorruptedException
	{ return FullDataPointUtil.encode(id, topY - bottomY, bottomY, (byte) 0, (byte) skyLight); }

	private static LongArrayList column(long... points) { return new LongArrayList(points); }

	/** @param ranges each data point's {bottom, top}, top down */
	private static void assertColumn(LongArrayList column, int[][] ranges)
	{
		StringBuilder actual = new StringBuilder();
		for (int i = 0; i < column.size(); i++)
		{
			actual.append(FullDataPointUtil.toString(column.getLong(i)));
		}

		Assert.assertEquals("column " + actual, ranges.length, column.size());
		for (int i = 0; i < ranges.length; i++)
		{
			long dataPoint = column.getLong(i);
			Assert.assertEquals("bottom of point " + i + " in " + actual, ranges[i][0], FullDataPointUtil.getBottomY(dataPoint));
			Assert.assertEquals("top of point " + i + " in " + actual, ranges[i][1], FullDataPointUtil.getBottomY(dataPoint) + FullDataPointUtil.getHeight(dataPoint));
		}
	}

}
