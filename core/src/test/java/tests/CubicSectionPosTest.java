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

import com.seibel.distanthorizons.core.enums.EDhDirection;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos;
import org.junit.Assert;
import org.junit.Test;

import java.util.HashSet;

/** Cubic (3D) section positions, see {@link DhSectionPos#encodeCubic(byte, int, int, int)}. */
public class CubicSectionPosTest
{
	@Test
	public void twoDimensionalLayoutIsUnchanged()
	{
		// the upstream encoding, written out by hand
		int[][] samples = { {6, 0, 0}, {6, 1, -3}, {14, -5, 7}, {0, 29_999_999, -29_999_999}, {9, -134_000_000 >> 9, 134_000_000 >> 9} };
		for (int[] sample : samples)
		{
			long expected = (sample[0] & 127L)
				| ((long) (sample[1] & ((1 << 28) - 1)) << 8)
				| ((long) (sample[2] & ((1 << 28) - 1)) << 36);
			long pos = DhSectionPos.encode((byte) sample[0], sample[1], sample[2]);
			Assert.assertEquals(expected, pos);
			Assert.assertFalse(DhSectionPos.isCubic(pos));
			Assert.assertEquals(0, DhSectionPos.getY(pos));
			Assert.assertEquals(4, DhSectionPos.getChildCount(pos));
		}
	}

	@Test
	public void encodeDecode()
	{
		int[] xs = { 0, 1, -1, 468_750, -468_751, (1 << 19) - 1, -(1 << 19) };
		int[] ys = { 0, 1, -1, 512, -513, (1 << 15) - 1, -(1 << 15) };
		for (byte detail = 6; detail <= 14; detail++)
		{
			for (int x : xs)
			{
				for (int y : ys)
				{
					int z = -x + 3;
					if (z >= (1 << 19) || z < -(1 << 19))
					{
						z = 3;
					}

					long pos = DhSectionPos.encodeCubic(detail, x, y, z);
					Assert.assertTrue(DhSectionPos.isCubic(pos));
					Assert.assertEquals(detail, DhSectionPos.getDetailLevel(pos));
					Assert.assertEquals(x, DhSectionPos.getX(pos));
					Assert.assertEquals(y, DhSectionPos.getY(pos));
					Assert.assertEquals(z, DhSectionPos.getZ(pos));
				}
			}
		}
	}

	@Test
	public void verticalGeometry()
	{
		Assert.assertEquals(64, DhSectionPos.getCubicBlockHeight((byte) 6));
		Assert.assertEquals(1024, DhSectionPos.getCubicBlockHeight((byte) 10));
		Assert.assertEquals(2048, DhSectionPos.getCubicBlockHeight((byte) 11));
		Assert.assertEquals(2048, DhSectionPos.getCubicBlockHeight((byte) 12));
		Assert.assertEquals(2048, DhSectionPos.getCubicBlockHeight((byte) 14));

		long pos = DhSectionPos.encodeCubic((byte) 6, 0, -2, 0);
		Assert.assertEquals(-128, DhSectionPos.getCubicMinBlockY(pos));
		Assert.assertEquals(-96, DhSectionPos.getCubicCenterBlockY(pos));
		Assert.assertEquals(-128, DhSectionPos.getMinBlockY(pos, -64));
		Assert.assertEquals(-64, DhSectionPos.getMinBlockY(DhSectionPos.encode((byte) 6, 0, 0), -64));

		pos = DhSectionPos.encodeCubic((byte) 13, 0, 3, 0);
		Assert.assertEquals(3 * 2048, DhSectionPos.getCubicMinBlockY(pos));
	}

	@Test
	public void childrenAndParents()
	{
		for (byte detail = 7; detail <= 14; detail++)
		{
			for (int y = -3; y <= 3; y++)
			{
				long parent = DhSectionPos.encodeCubic(detail, -3, y, 5);
				int expectedCount = detail <= DhSectionPos.CUBIC_MAX_VERTICAL_DETAIL_LEVEL ? 8 : 4;
				Assert.assertEquals(expectedCount, DhSectionPos.getChildCount(parent));

				HashSet<Long> children = new HashSet<>();
				for (int i = 0; i < expectedCount; i++)
				{
					long child = DhSectionPos.getChildByIndex(parent, i);
					Assert.assertTrue(DhSectionPos.isCubic(child));
					Assert.assertEquals(detail - 1, DhSectionPos.getDetailLevel(child));
					Assert.assertTrue("parent must contain child " + DhSectionPos.toString(child), DhSectionPos.contains(parent, child));
					// contains() tests the second position's min corner, which child 0 shares with its parent
					Assert.assertEquals(i == 0, DhSectionPos.contains(child, parent));
					Assert.assertEquals(parent, DhSectionPos.getParentPos(child));
					Assert.assertEquals(i, DhSectionPos.getChildIndexOfParent(child));
					Assert.assertTrue("children must be unique", children.add(child));
				}

				// the children cover the parent exactly
				int childHeight = DhSectionPos.getCubicBlockHeight((byte) (detail - 1));
				int childVolume = DhSectionPos.getBlockWidth(DhSectionPos.getChildByIndex(parent, 0));
				Assert.assertEquals(
					(long) DhSectionPos.getBlockWidth(parent) * DhSectionPos.getBlockWidth(parent) * DhSectionPos.getCubicBlockHeight(parent),
					(long) childVolume * childVolume * childHeight * expectedCount);
			}
		}

		Assert.assertThrows(IllegalArgumentException.class, () -> DhSectionPos.getChildByIndex(DhSectionPos.encodeCubic((byte) 12, 0, 0, 0), 4));
	}

	@Test
	public void convertToDetailLevel()
	{
		long leaf = DhSectionPos.encodeCubic((byte) 6, -5, -9, 17);

		// up to a cube
		long up = DhSectionPos.convertToDetailLevel(leaf, (byte) 8);
		Assert.assertEquals(DhSectionPos.encodeCubic((byte) 8, -2, -3, 4), up);
		Assert.assertTrue(DhSectionPos.contains(up, leaf));

		// up to a slab (vertical height stops at 2048)
		long slab = DhSectionPos.convertToDetailLevel(leaf, (byte) 13);
		Assert.assertEquals(DhSectionPos.encodeCubic((byte) 13, -1, -1, 0), slab);
		Assert.assertTrue(DhSectionPos.contains(slab, leaf));

		// every ancestor is reachable through getParentPos
		long walk = leaf;
		for (byte detail = 7; detail <= 14; detail++)
		{
			walk = DhSectionPos.getParentPos(walk);
			Assert.assertEquals(DhSectionPos.convertToDetailLevel(leaf, detail), walk);
		}

		// down: the lowest corner child
		long down = DhSectionPos.convertToDetailLevel(up, (byte) 6);
		Assert.assertEquals(DhSectionPos.encodeCubic((byte) 6, -8, -12, 16), down);

		// below the section levels positions are 2D helpers
		long block = DhSectionPos.convertToDetailLevel(leaf, (byte) 0);
		Assert.assertFalse(DhSectionPos.isCubic(block));
		Assert.assertEquals(-5 * 64, DhSectionPos.getX(block));
		Assert.assertEquals(17 * 64, DhSectionPos.getZ(block));
	}

	@Test
	public void encodeContaining()
	{
		long pos = DhSectionPos.encodeContainingCubic((byte) 6, -1, -1, 0);
		Assert.assertEquals(DhSectionPos.encodeCubic((byte) 6, -1, -1, 0), pos);

		pos = DhSectionPos.encodeContainingCubic((byte) 6, 63, 64, -64);
		Assert.assertEquals(DhSectionPos.encodeCubic((byte) 6, 0, 1, -1), pos);

		pos = DhSectionPos.encodeContainingCubic((byte) 12, 5000, -2049, 0);
		Assert.assertEquals(DhSectionPos.encodeCubic((byte) 12, 1, -2, 0), pos);

		Assert.assertTrue(DhSectionPos.contains(pos, new DhBlockPos(5000, -2049, 0)));
		Assert.assertFalse(DhSectionPos.contains(pos, new DhBlockPos(5000, -2048, 0)));
		Assert.assertTrue(DhSectionPos.contains(pos, new DhBlockPos(5000, -4096, 0)));
	}

	@Test
	public void containsChecksY()
	{
		long lower = DhSectionPos.encodeCubic((byte) 7, 0, 0, 0);
		long upperChild = DhSectionPos.encodeCubic((byte) 6, 0, 2, 0);
		Assert.assertFalse(DhSectionPos.contains(lower, upperChild));
		Assert.assertTrue(DhSectionPos.contains(DhSectionPos.encodeCubic((byte) 7, 0, 1, 0), upperChild));
	}

	@Test
	public void adjacentPositions()
	{
		long pos = DhSectionPos.encodeCubic((byte) 6, 0, 0, 0);
		Assert.assertEquals(DhSectionPos.encodeCubic((byte) 6, 0, 1, 0), DhSectionPos.getAdjacentPos(pos, EDhDirection.UP));
		Assert.assertEquals(DhSectionPos.encodeCubic((byte) 6, 0, -1, 0), DhSectionPos.getAdjacentPos(pos, EDhDirection.DOWN));
		Assert.assertEquals(DhSectionPos.encodeCubic((byte) 6, 0, 0, -1), DhSectionPos.getAdjacentPos(pos, EDhDirection.NORTH));
		Assert.assertEquals(DhSectionPos.encodeCubic((byte) 6, 1, 0, 0), DhSectionPos.getAdjacentPos(pos, EDhDirection.EAST));
	}

	@Test
	public void distance()
	{
		long pos = DhSectionPos.encodeCubic((byte) 6, 0, 1, 0);
		// center is (32, 96, 32)
		Assert.assertEquals(0.0, DhSectionPos.getSquaredBlockDistanceToCenter(pos, 32, 96, 32), 0.0);
		Assert.assertEquals(100.0 * 100.0, DhSectionPos.getSquaredBlockDistanceToCenter(pos, 32, -4, 32), 0.0);
		Assert.assertEquals(100L, DhSectionPos.getManhattanBlockDistance(pos, new DhBlockPos(32, -4, 32)));

		// 2D positions ignore Y
		long flat = DhSectionPos.encode((byte) 6, 0, 0);
		Assert.assertEquals(0.0, DhSectionPos.getSquaredBlockDistanceToCenter(flat, 32, 1000, 32), 0.0);
	}

}
