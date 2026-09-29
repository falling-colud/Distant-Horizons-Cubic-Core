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

import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos2D;
import com.seibel.distanthorizons.core.util.objects.quadTree.QuadNode;
import com.seibel.distanthorizons.core.util.objects.quadTree.QuadTree;
import it.unimi.dsi.fastutil.longs.LongIterator;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;

/** The octree form of {@link QuadTree} used by cubic levels. */
public class CubicTreeTest
{
	/** 1024 blocks across, root detail 10 (1024 wide, 1024 tall) */
	private static final int DIAMETER = 1024;
	private static final byte LEAF = DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL;

	private static QuadTree<Integer> newTree(int centerY, int verticalRadius, int levelMinY, int levelMaxY)
	{ return new QuadTree<>(DIAMETER, 64, DhBlockPos2D.ZERO, centerY, LEAF, verticalRadius, levelMinY, levelMaxY); }



	@Test
	public void rootLayersFollowTheWindow()
	{
		QuadTree<Integer> tree = newTree(0, 1500, -32768, 32768);
		Assert.assertTrue(tree.isCubic());
		Assert.assertEquals(10, tree.treeRootDetailLevel);
		// window [-1500, 1501) -> layers floor(-1500/1024) = -2 .. floor(1500/1024) = 1
		Assert.assertEquals(4, tree.rootLayerCount());

		// clamped to the level: [-64, 320) touches layers -1 and 0
		tree = newTree(0, 1500, -64, 320);
		Assert.assertEquals(2, tree.rootLayerCount());

		// a center far above the level: nothing is in bounds
		tree = newTree(100_000, 1500, -64, 320);
		Assert.assertEquals(1, tree.rootLayerCount());
		Assert.assertFalse(tree.isSectionPosInBounds(DhSectionPos.encodeCubic((byte) 6, 0, 0, 0)));
	}

	@Test
	public void setAndGetAcrossLayers()
	{
		QuadTree<Integer> tree = newTree(0, 1500, -32768, 32768);

		HashSet<Long> positions = new HashSet<>();
		int value = 0;
		for (int y = -20; y <= 20; y += 3)
		{
			for (int x = -3; x <= 3; x += 2)
			{
				long pos = DhSectionPos.encodeCubic(LEAF, x, y, -x);
				if (!tree.isSectionPosInBounds(pos))
				{
					continue;
				}
				tree.setValue(pos, value);
				Assert.assertEquals(Integer.valueOf(value), tree.getValue(pos));
				positions.add(pos);
				value++;
			}
		}
		Assert.assertFalse(positions.isEmpty());

		// intermediate nodes exist (with null values) for every set position
		for (long pos : positions)
		{
			long parent = DhSectionPos.getParentPos(pos);
			QuadNode<Integer> parentNode = tree.getNode(parent);
			Assert.assertNotNull(parentNode);
			Assert.assertEquals(8, parentNode.getChildSlotCount());
			Assert.assertNotNull(parentNode.getChildByIndex(DhSectionPos.getChildIndexOfParent(pos)));
		}

		// the node iterator sees every value
		HashSet<Integer> seen = new HashSet<>();
		Iterator<QuadNode<Integer>> iterator = tree.nodeIterator();
		while (iterator.hasNext())
		{
			QuadNode<Integer> node = iterator.next();
			if (node.value != null)
			{
				seen.add(node.value);
			}
		}
		Assert.assertEquals(positions.size(), seen.size());

		// every root position the iterator hands out is cubic and in bounds
		LongIterator rootIterator = tree.rootNodePosIterator();
		int rootCount = 0;
		while (rootIterator.hasNext())
		{
			long rootPos = rootIterator.nextLong();
			Assert.assertTrue(DhSectionPos.isCubic(rootPos));
			Assert.assertEquals(tree.treeRootDetailLevel, DhSectionPos.getDetailLevel(rootPos));
			Assert.assertTrue(tree.isSectionPosInBounds(rootPos));
			rootCount++;
		}
		// of the 3x3 ring only the 2x2 roots touching the 1024 wide tree are in bounds, 4 layers
		Assert.assertEquals(4 * 4, rootCount);
	}

	@Test
	public void twoDimensionalPositionsAreOutOfBounds()
	{
		QuadTree<Integer> tree = newTree(0, 1500, -32768, 32768);
		Assert.assertFalse(tree.isSectionPosInBounds(DhSectionPos.encode(LEAF, 0, 0)));
		Assert.assertNull(tree.tryGetNode(DhSectionPos.encode(LEAF, 0, 0)));
	}

	@Test
	public void verticalBounds()
	{
		QuadTree<Integer> tree = newTree(100, 200, -32768, 32768);
		// window [-100, 301)
		Assert.assertTrue(tree.isSectionPosInBounds(DhSectionPos.encodeCubic(LEAF, 0, 0, 0)));   // [0, 64)
		Assert.assertTrue(tree.isSectionPosInBounds(DhSectionPos.encodeCubic(LEAF, 0, -2, 0)));  // [-128, -64)
		Assert.assertFalse(tree.isSectionPosInBounds(DhSectionPos.encodeCubic(LEAF, 0, -3, 0))); // [-192, -128)
		Assert.assertTrue(tree.isSectionPosInBounds(DhSectionPos.encodeCubic(LEAF, 0, 4, 0)));   // [256, 320)
		Assert.assertFalse(tree.isSectionPosInBounds(DhSectionPos.encodeCubic(LEAF, 0, 5, 0)));  // [320, 384)

		Assert.assertThrows(IndexOutOfBoundsException.class, () -> tree.setValue(DhSectionPos.encodeCubic(LEAF, 0, 5, 0), 1));
	}

	@Test
	public void movingVerticallyRemovesLayers()
	{
		QuadTree<Integer> tree = newTree(0, 600, -32768, 32768);
		// window [-600, 601) -> layers -1..0
		Assert.assertEquals(2, tree.rootLayerCount());

		long low = DhSectionPos.encodeCubic(LEAF, 1, -5, 1);   // [-320, -256), layer -1
		long high = DhSectionPos.encodeCubic(LEAF, 1, 5, 1);   // [320, 384), layer 0
		tree.setValue(low, 1);
		tree.setValue(high, 2);

		ArrayList<Integer> removed = new ArrayList<>();
		ArrayList<Integer> mutated = new ArrayList<>();

		// move up by 2000: window [1400, 2601) -> layers 1..2, both old layers go
		tree.setCenterBlockPos(DhBlockPos2D.ZERO, 2000, removed::add, mutated::add);
		Assert.assertEquals(2, tree.rootLayerCount());
		Assert.assertTrue("removed values " + removed, removed.contains(1));
		Assert.assertTrue("removed values " + removed, removed.contains(2));
		Assert.assertNull(tree.tryGetValue(low));
		Assert.assertNull(tree.tryGetValue(high));
		Assert.assertEquals(0, tree.nodeCount());

		// set something in the new window and come back down a little: layer 1 survives
		long kept = DhSectionPos.encodeCubic(LEAF, 0, 20, 0); // [1280, 1344), layer 1
		tree.setCenterBlockPos(DhBlockPos2D.ZERO, 1500, removed::add, mutated::add); // window [900, 2101) -> layers 0..2
		tree.setValue(kept, 3);
		// like the LOD tree, every node on the way down holds a value (the mutate pass stops at empty nodes)
		for (long ancestor = DhSectionPos.getParentPos(kept); DhSectionPos.getDetailLevel(ancestor) <= tree.treeRootDetailLevel; ancestor = DhSectionPos.getParentPos(ancestor))
		{
			tree.setValue(ancestor, 100 + DhSectionPos.getDetailLevel(ancestor));
		}
		removed.clear();
		tree.setCenterBlockPos(DhBlockPos2D.ZERO, 1200, removed::add, mutated::add); // window [600, 1801) -> layers 0..1
		Assert.assertEquals(Integer.valueOf(3), tree.getValue(kept));
		Assert.assertFalse(removed.contains(3));

		// moving so the section leaves the window without dropping its layer only mutates it
		mutated.clear();
		tree.setCenterBlockPos(DhBlockPos2D.ZERO, 2000, removed::add, mutated::add); // window [1400, 2601) -> layers 1..2
		Assert.assertTrue("mutated values " + mutated, mutated.contains(3));
		Assert.assertFalse(removed.contains(3));
	}

	@Test
	public void slabsSplitIntoFour()
	{
		// root detail 13 -> 2048 tall slabs above detail 11
		QuadTree<Integer> tree = new QuadTree<>(8192, 64, DhBlockPos2D.ZERO, 0, LEAF, 4096, -32768, 32768);
		Assert.assertEquals(13, tree.treeRootDetailLevel);

		long slab = DhSectionPos.encodeCubic((byte) 12, 0, 0, 0);
		tree.setValue(slab, 1);
		long cube = DhSectionPos.encodeCubic((byte) 11, 0, 0, 0);
		tree.setValue(cube, 2);
		long leaf = DhSectionPos.encodeCubic(LEAF, 5, 31, 5);
		tree.setValue(leaf, 3);

		Assert.assertEquals(4, tree.getNode(slab).getChildSlotCount());
		Assert.assertEquals(8, tree.getNode(cube).getChildSlotCount());
		Assert.assertEquals(Integer.valueOf(3), tree.getValue(leaf));
		Assert.assertEquals(Integer.valueOf(2), tree.getValue(cube));
		Assert.assertEquals(Integer.valueOf(1), tree.getValue(slab));
	}

}
