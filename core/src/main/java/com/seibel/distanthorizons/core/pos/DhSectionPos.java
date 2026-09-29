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

package com.seibel.distanthorizons.core.pos;

import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.enums.EDhDirection;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos2D;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.coreapi.util.BitShiftUtil;

import java.util.function.LongConsumer;

/**
 * The position object used to define LOD objects in the quad trees. <br><br>
 *
 * A section contains 64 x 64 LOD columns at a given quality.
 * The Section detail level is different from the LOD detail level.
 * For the specifics of how they compare can be viewed in the constants {@link #SECTION_BLOCK_DETAIL_LEVEL},
 * {@link #SECTION_CHUNK_DETAIL_LEVEL}, and {@link #SECTION_REGION_DETAIL_LEVEL}).<br><br>
 *
 * <strong>Why does the smallest render section represent 4x4 MC chunks (section detail level 6)? </strong> <br>
 * A section defines what unit the quad tree works in, because of that we don't want that unit to be too big or too small. <br>
 * <strong>Too small</strong>, and we'll have 1,000s of sections running around, all needing individual files and render buffers.<br>
 * <strong>Too big</strong>, and the LOD dropoff will be very noticeable.<br>
 * With those thoughts in mind we decided on a smallest section size of 64 data points square (IE 4x4 chunks).
 * <br><br>
 *
 * <strong>Cubic positions</strong><br>
 * A level with cubic LODs (see {@link #isCubic(long)}) uses positions that also have a Y index:
 * each section is a cube of {@code 2^detail} blocks (a slab {@link #CUBIC_MAX_VERTICAL_DETAIL_LEVEL}
 * blocks tall above that detail level), so the tree is an octree and LODs lose
 * detail vertically as well as horizontally. Cubic positions set bit 7 of the detail byte,
 * which upstream positions never use, so every non-cubic position keeps the exact bits
 * (and database keys and network bytes) of an unmodified Distant Horizons.
 *
 * @author Leetom
 */
public class DhSectionPos
{
	/**
	 * The lowest detail level a Section position can hold.
	 * This section DetailLevel holds 64 x 64 Block level (detail level 0) LODs.
	 */
	public static final byte SECTION_MINIMUM_DETAIL_LEVEL = 6;

	public static final byte SECTION_BLOCK_DETAIL_LEVEL = SECTION_MINIMUM_DETAIL_LEVEL + LodUtil.BLOCK_DETAIL_LEVEL;
	public static final byte SECTION_CHUNK_DETAIL_LEVEL = SECTION_MINIMUM_DETAIL_LEVEL + LodUtil.CHUNK_DETAIL_LEVEL;
	public static final byte SECTION_REGION_DETAIL_LEVEL = SECTION_MINIMUM_DETAIL_LEVEL + LodUtil.REGION_DETAIL_LEVEL;



	public static final int DETAIL_LEVEL_WIDTH = 8;
	public static final int X_POS_WIDTH = 28;
	public static final int Z_POS_WIDTH = 28;
	public static final int X_POS_MISSING_WIDTH = 32 - 28;
	public static final int Z_POS_MISSING_WIDTH = 32 - 28;


	public static final int DETAIL_LEVEL_OFFSET = 0;
	public static final int POS_X_OFFSET = DETAIL_LEVEL_OFFSET + DETAIL_LEVEL_WIDTH;
	public static final int POS_Z_OFFSET = POS_X_OFFSET + X_POS_WIDTH;

	public static final long DETAIL_LEVEL_MASK = Byte.MAX_VALUE;
	public static final int POS_X_MASK = (int) Math.pow(2, X_POS_WIDTH) - 1;
	public static final int POS_Z_MASK = (int) Math.pow(2, Z_POS_WIDTH) - 1;



	//==============//
	// cubic layout //
	//==============//

	/** Set on cubic (3D) positions. Upstream positions keep the detail level below 128, so this bit is always 0 for them. */
	public static final long CUBIC_FLAG = 1L << 7;

	public static final int CUBIC_X_WIDTH = 20;
	public static final int CUBIC_Z_WIDTH = 20;
	public static final int CUBIC_Y_WIDTH = 16;

	public static final int CUBIC_X_OFFSET = DETAIL_LEVEL_WIDTH;
	public static final int CUBIC_Z_OFFSET = CUBIC_X_OFFSET + CUBIC_X_WIDTH;
	public static final int CUBIC_Y_OFFSET = CUBIC_Z_OFFSET + CUBIC_Z_WIDTH;

	public static final int CUBIC_X_MASK = (1 << CUBIC_X_WIDTH) - 1;
	public static final int CUBIC_Z_MASK = (1 << CUBIC_Z_WIDTH) - 1;
	public static final int CUBIC_Y_MASK = (1 << CUBIC_Y_WIDTH) - 1;

	/**
	 * The detail level at which cubic sections stop growing vertically: sections at this detail
	 * level and above are {@code 2^11 = 2048} blocks tall. <br>
	 * Full and render data points store heights in 12 bits, which can't hold a 4096 block tall column.
	 */
	public static final byte CUBIC_MAX_VERTICAL_DETAIL_LEVEL = 11;



	//==============//
	// constructors //
	//==============//
	//region

	/**
	 * This class just holds utility methods for handling a packed
	 * {@link DhSectionPos} and shouldn't be constructed. <Br><br>
	 *
	 * Use one of the {@link DhSectionPos#encode(byte, int, int)} methods instead
	 */
	private DhSectionPos() { }



	/**
	 * Note:
	 * no validation is done for whether the detail level is positive
	 * or if the X/Z positions can be represented by available bits.
	 */
	public static long encode(byte detailLevel, int x, int z)
	{
		long data = 0;
		data |= detailLevel & DETAIL_LEVEL_MASK;
		data |= (long) (x & POS_X_MASK) << POS_X_OFFSET;
		data |= (long) (z & POS_Z_MASK) << POS_Z_OFFSET;
		return data;
	}

	/**
	 * Encodes a cubic (3D) section position. <br>
	 * The section covers blocks {@code [y * height, (y + 1) * height)} vertically,
	 * where the height is {@link #getCubicBlockHeight(byte)}. <br><br>
	 *
	 * Note: X and Z are stored in {@value #CUBIC_X_WIDTH} bits,
	 * which covers the whole world at every section detail level (&gt;= 6);
	 * Y is stored in {@value #CUBIC_Y_WIDTH} bits.
	 */
	public static long encodeCubic(byte detailLevel, int x, int y, int z)
	{
		long data = CUBIC_FLAG;
		data |= detailLevel & DETAIL_LEVEL_MASK;
		data |= (long) (x & CUBIC_X_MASK) << CUBIC_X_OFFSET;
		data |= (long) (z & CUBIC_Z_MASK) << CUBIC_Z_OFFSET;
		data |= (long) (y & CUBIC_Y_MASK) << CUBIC_Y_OFFSET;
		return data;
	}

	/** Returns the section pos at the requested detail level containing the given BlockPos */
	public static long encodeContaining(byte outputSectionDetailLevel, DhBlockPos pos)
	{
		int sectionPosX = getXOrZSectionPosFromChunkOrBlockPos(pos.getX(), false);
		int sectionPosZ = getXOrZSectionPosFromChunkOrBlockPos(pos.getZ(), false);
		long blockPos = DhSectionPos.encode(DhSectionPos.SECTION_BLOCK_DETAIL_LEVEL, sectionPosX, sectionPosZ);
		return convertToDetailLevel(blockPos, outputSectionDetailLevel);
	}
	/** Returns the section pos at the requested detail level containing the given ChunkPos */
	public static long encodeContaining(byte outputSectionDetailLevel, DhChunkPos pos)
	{
		int sectionPosX = getXOrZSectionPosFromChunkOrBlockPos(pos.getX(), true);
		int sectionPosZ = getXOrZSectionPosFromChunkOrBlockPos(pos.getZ(), true);
		long blockPos = DhSectionPos.encode(DhSectionPos.SECTION_BLOCK_DETAIL_LEVEL, sectionPosX, sectionPosZ);
		return convertToDetailLevel(blockPos, outputSectionDetailLevel);
	}
	/**
	 * Returns the cubic section pos at the requested section detail level containing the given block.
	 * @param outputSectionDetailLevel must be at least {@link #SECTION_MINIMUM_DETAIL_LEVEL}
	 */
	public static long encodeContainingCubic(byte outputSectionDetailLevel, int blockX, int blockY, int blockZ)
	{
		if (outputSectionDetailLevel < SECTION_MINIMUM_DETAIL_LEVEL)
		{
			throw new IllegalArgumentException("Cubic section positions need a section detail level of at least ["+SECTION_MINIMUM_DETAIL_LEVEL+"], given ["+outputSectionDetailLevel+"].");
		}

		return encodeCubic(outputSectionDetailLevel,
			Math.floorDiv(blockX, 1 << outputSectionDetailLevel),
			Math.floorDiv(blockY, 1 << getCubicVerticalDetailLevel(outputSectionDetailLevel)),
			Math.floorDiv(blockZ, 1 << outputSectionDetailLevel));
	}
	private static int getXOrZSectionPosFromChunkOrBlockPos(int chunkXOrZPos, boolean isChunkPos)
	{
		int sectionPos = chunkXOrZPos;
		int fullDataSourceWidth = isChunkPos ? FullDataSourceV2.NUMB_OF_CHUNKS_WIDE : (FullDataSourceV2.NUMB_OF_CHUNKS_WIDE * LodUtil.CHUNK_WIDTH);

		// negative positions start at -1 so the logic there is slightly different
		sectionPos = (sectionPos < 0)
				? ((sectionPos + 1) / fullDataSourceWidth) - 1
				: (sectionPos / fullDataSourceWidth);
		return sectionPos;
	}

	//endregion



	//============//
	// converters //
	//============//
	//region

	/**
	 * uses the absolute detail level aka detail levels like {@link LodUtil#CHUNK_DETAIL_LEVEL} instead of the dhSectionPos detailLevels. <br><br>
	 *
	 * A cubic position converted below {@link #SECTION_MINIMUM_DETAIL_LEVEL} becomes a 2D position:
	 * positions that small are only used for horizontal block and column math.
	 */
	public static long convertToDetailLevel(long pos, byte newDetailLevel)
	{
		byte detailLevel = getDetailLevel(pos);
		int x = getX(pos);
		int z = getZ(pos);

		// logic originally taken from DhLodPos
		if (newDetailLevel >= detailLevel)
		{
			x = Math.floorDiv(x, BitShiftUtil.powerOfTwo(newDetailLevel - detailLevel));
			z = Math.floorDiv(z, BitShiftUtil.powerOfTwo(newDetailLevel - detailLevel));
		}
		else
		{
			x = x * BitShiftUtil.powerOfTwo(detailLevel - newDetailLevel);
			z = z * BitShiftUtil.powerOfTwo(detailLevel - newDetailLevel);
		}

		if (!isCubic(pos)
			|| newDetailLevel < SECTION_MINIMUM_DETAIL_LEVEL)
		{
			return encode(newDetailLevel, x, z);
		}


		// cubic, convert the vertical index as well
		int y = getY(pos);
		int oldVerticalDetail = getCubicVerticalDetailLevel(detailLevel);
		int newVerticalDetail = getCubicVerticalDetailLevel(newDetailLevel);
		if (newVerticalDetail >= oldVerticalDetail)
		{
			// arithmetic shift is a floor division, which is correct for negative positions
			y = y >> (newVerticalDetail - oldVerticalDetail);
		}
		else
		{
			y = y << (oldVerticalDetail - newVerticalDetail);
		}

		return encodeCubic(newDetailLevel, x, y, z);
	}

	//endregion



	//==================//
	// property getters //
	//==================//
	//region

	public static byte getDetailLevel(long pos) { return (byte) ((pos >> DETAIL_LEVEL_OFFSET) & DETAIL_LEVEL_MASK); }
	public static int getX(long pos)
	{
		if (isCubic(pos))
		{
			int x = (int) ((pos >> CUBIC_X_OFFSET) & CUBIC_X_MASK);
			return (x << (32 - CUBIC_X_WIDTH)) >> (32 - CUBIC_X_WIDTH);
		}

		// unpack the position
		int x = (int) ((pos >> POS_X_OFFSET) & POS_X_MASK);
		// add the missing 2's compliment most-significant bits (if not done negative numbers will parse incorrectly)
		x = (x << X_POS_MISSING_WIDTH) >> X_POS_MISSING_WIDTH;
		return x;
	}
	public static int getZ(long pos)
	{
		if (isCubic(pos))
		{
			int z = (int) ((pos >> CUBIC_Z_OFFSET) & CUBIC_Z_MASK);
			return (z << (32 - CUBIC_Z_WIDTH)) >> (32 - CUBIC_Z_WIDTH);
		}

		int Z = (int) ((pos >> POS_Z_OFFSET) & POS_Z_MASK);
		Z = (Z << Z_POS_MISSING_WIDTH) >> Z_POS_MISSING_WIDTH;
		return Z;
	}
	/** @return the vertical index of a cubic position, 0 for 2D positions */
	public static int getY(long pos)
	{
		if (!isCubic(pos))
		{
			return 0;
		}

		int y = (int) ((pos >>> CUBIC_Y_OFFSET) & CUBIC_Y_MASK);
		return (y << (32 - CUBIC_Y_WIDTH)) >> (32 - CUBIC_Y_WIDTH);
	}

	/** @return true if this position is a cubic (3D) position */
	public static boolean isCubic(long pos) { return (pos & CUBIC_FLAG) != 0; }

	//endregion



	//=================//
	// cubic geometry  //
	//=================//
	//region

	/**
	 * @return log2 of the height of a cubic section at the given section detail level:
	 * the detail level itself, capped at {@link #CUBIC_MAX_VERTICAL_DETAIL_LEVEL}.
	 */
	public static byte getCubicVerticalDetailLevel(byte sectionDetailLevel) { return (byte) Math.min(sectionDetailLevel, CUBIC_MAX_VERTICAL_DETAIL_LEVEL); }
	/** @return how tall a cubic section at the given section detail level is, in blocks */
	public static int getCubicBlockHeight(byte sectionDetailLevel) { return 1 << getCubicVerticalDetailLevel(sectionDetailLevel); }

	/** @return true if a cubic section at this detail level splits into two vertical halves as well as four quadrants */
	public static boolean cubicSplitsVertically(byte sectionDetailLevel)
	{ return sectionDetailLevel > SECTION_MINIMUM_DETAIL_LEVEL && getCubicVerticalDetailLevel((byte) (sectionDetailLevel - 1)) < getCubicVerticalDetailLevel(sectionDetailLevel); }

	/** @return the lowest block Y of a cubic section */
	public static int getCubicMinBlockY(long cubicPos)
	{
		assertCubic(cubicPos);
		return getY(cubicPos) << getCubicVerticalDetailLevel(getDetailLevel(cubicPos));
	}
	/** @return the height of a cubic section in blocks */
	public static int getCubicBlockHeight(long cubicPos)
	{
		assertCubic(cubicPos);
		return getCubicBlockHeight(getDetailLevel(cubicPos));
	}

	/**
	 * @return the Y that this section's data point heights are relative to:
	 * the section's own bottom for cubic positions, the level's minimum height otherwise.
	 */
	public static int getMinBlockY(long pos, int levelMinY) { return isCubic(pos) ? getCubicMinBlockY(pos) : levelMinY; }
	/** @return the height of the data held by this section: its own height for cubic positions, the level's height otherwise */
	public static int getBlockHeight(long pos, int levelHeight) { return isCubic(pos) ? getCubicBlockHeight(pos) : levelHeight; }

	/** @return the block Y at the vertical center of a cubic section */
	public static int getCubicCenterBlockY(long cubicPos) { return getCubicMinBlockY(cubicPos) + (getCubicBlockHeight(cubicPos) / 2); }

	private static void assertCubic(long pos)
	{
		if (!isCubic(pos))
		{
			throw new IllegalArgumentException("The position ["+toString(pos)+"] isn't cubic.");
		}
	}

	//endregion



	//=================//
	// complex getters //
	//=================//
	//region

	/** @return the block X pos that represents the smallest X coordinate of this section */
	public static int getMinCornerBlockX(long pos)
	{
		// detail level 1 (2x2 blocks) is a special case,
		// if this isn't done it will return (1,1) instead of (0,0)
		int halfBlockWidth = (getDetailLevel(pos) != 1) ? (DhSectionPos.getBlockWidth(pos) / 2) : 0;
		return DhSectionPos.getCenterBlockPosX(pos) - halfBlockWidth;
	}
	/** @return the block Z pos that represents the smallest Z coordinate of this section */
	public static int getMinCornerBlockZ(long pos)
	{
		int halfBlockWidth = (getDetailLevel(pos) != 1) ? (DhSectionPos.getBlockWidth(pos) / 2) : 0;
		return DhSectionPos.getCenterBlockPosZ(pos) - halfBlockWidth;
	}

	/**
	 * A detail level of X lower than this section's detail level will return: <br>
	 * 0 -> 1 <br>
	 * 1 -> 2 <br>
	 * 2 -> 4 <br>
	 * 3 -> 8 <br>
	 * etc.
	 *
	 * @return how many {@link DhSectionPos}'s at the given detail level it would take to span the width of this section.
	 */
	public static int getWidthCountForLowerDetailedSection(long pos, byte returnDetailLevel)
	{
		byte detailLevel = getDetailLevel(pos);

		LodUtil.assertTrue(returnDetailLevel <= detailLevel, "returnDetailLevel must be less than sectionDetail");
		byte offset = (byte) (detailLevel - returnDetailLevel);
		return BitShiftUtil.powerOfTwo(offset);
	}


	/** @return how wide this section is in chunks */
	public static int getChunkWidth(long pos) { return DhSectionPos.getBlockWidth(pos) / LodUtil.CHUNK_WIDTH; }
	/** @see DhSectionPos#getDetailLevelWidthInBlocks(byte) */
	public static int getBlockWidth(long pos) { return getDetailLevelWidthInBlocks(getDetailLevel(pos)); }
	/**
	 * Returns how many blocks wide a single LOD at the given detail level would be in blocks. <br>
	 * IE: <br>
	 * <code>
	 * 0 => 1 <br>
	 * 1 => 2 <br>
	 * 2 => 4 <br>
	 * 3 => 8 <br>
	 * 4 => 16 <br>
	 * 5 => 32 <br>
	 * 6 => 64 <br>
	 * etc. <br>
	 * </code>
	 */
	public static int getDetailLevelWidthInBlocks(byte detailLevel) { return BitShiftUtil.powerOfTwo(detailLevel); }


	public static DhBlockPos2D getCenterBlockPos(long pos) { return new DhBlockPos2D(getCenterBlockPosX(pos), getCenterBlockPosZ(pos)); }

	public static int getCenterBlockPosX(long pos) { return getCenterBlockPosXOrZ(pos, true); }
	public static int getCenterBlockPosZ(long pos) { return getCenterBlockPosXOrZ(pos, false); }
	private static int getCenterBlockPosXOrZ(long pos, boolean returnX)
	{
		byte detailLevel = getDetailLevel(pos);
		int x = getX(pos);
		int z = getZ(pos);


		int centerBlockPos = returnX ? x : z;

		if (detailLevel == 0)
		{
			// already at block detail level, no conversion necessary
			return centerBlockPos;
		}

		// we can't get the center of the position at block level, only attempt to get the position offset for detail levels above 0
		int positionOffset = 0;
		if (detailLevel != 1)
		{
			positionOffset = BitShiftUtil.powerOfTwo(detailLevel - 1);
		}

		return (centerBlockPos * BitShiftUtil.powerOfTwo(detailLevel)) + positionOffset;
	}

	public static int getManhattanBlockDistance(long pos, DhBlockPos2D blockPos)
	{
		return Math.abs(getCenterBlockPosX(pos) - blockPos.x)
				+ Math.abs(getCenterBlockPosZ(pos) - blockPos.z);
	}
	/**
	 * Like {@link #getManhattanBlockDistance(long, DhBlockPos2D)},
	 * but includes the vertical distance for cubic positions.
	 */
	public static long getManhattanBlockDistance(long pos, DhBlockPos blockPos)
	{
		long distance = Math.abs((long) getCenterBlockPosX(pos) - blockPos.getX())
				+ Math.abs((long) getCenterBlockPosZ(pos) - blockPos.getZ());
		if (isCubic(pos))
		{
			distance += Math.abs((long) getCubicCenterBlockY(pos) - blockPos.getY());
		}
		return distance;
	}

	/**
	 * @return the squared distance between the given block and this section's center,
	 * including the vertical distance for cubic positions
	 */
	public static double getSquaredBlockDistanceToCenter(long pos, double blockX, double blockY, double blockZ)
	{
		double dx = getCenterBlockPosX(pos) - blockX;
		double dz = getCenterBlockPosZ(pos) - blockZ;
		double distance = (dx * dx) + (dz * dz);
		if (isCubic(pos))
		{
			double dy = getCubicCenterBlockY(pos) - blockY;
			distance += dy * dy;
		}
		return distance;
	}


	/** see: {@link DhSectionPos#getChebyshevSignedBlockDistance(long, int, int)} */
	public static int getChebyshevSignedBlockDistance(long pos, DhBlockPos  blockPos)
	{ return getChebyshevSignedBlockDistance(pos, blockPos.getX(), blockPos.getZ()); }
	/** see: {@link DhSectionPos#getChebyshevSignedBlockDistance(long, int, int)} */
	public static int getChebyshevSignedBlockDistance(long pos, DhBlockPos2D blockPos)
	{ return getChebyshevSignedBlockDistance(pos, blockPos.x, blockPos.z); }
	/**
	 * Returns the signed distance from a given block to a given section. <br>
	 * Essentially acts like a distance from the block to the nearest edge of the section,
	 * except inside the section it's negative. <br>
	 * Useful for detail level insensitive distance comparisons.
	 */
	public static int getChebyshevSignedBlockDistance(long pos, int blockPosX, int blockPosZ)
	{
		return Math.max(
			Math.abs(getCenterBlockPosX(pos) - blockPosX),
			Math.abs(getCenterBlockPosZ(pos) - blockPosZ)
		) - getBlockWidth(pos) / 2;
	}


	/**
	 * Returns a {@link DhSectionPos} with the given detail level and an X/Z position somewhere between (0,0) and (63,63).
	 * This is done to access specific sections from a {@link FullDataSourceV2} where LOD columns are stored
	 * in 64 x 64 blocks.
	 *
	 * @throws IllegalArgumentException if this position's detail level is lower than the output detail level
	 * @see FullDataSourceV2#WIDTH
	 */
	public static long getDhSectionRelativePositionForDetailLevel(long pos, byte outputDetailLevel) throws IllegalArgumentException
	{
		final int xInputOriginal = DhSectionPos.getX(pos);
		final int zInputOriginal = DhSectionPos.getZ(pos);

		byte detailLevelDifference = (byte) (outputDetailLevel - DhSectionPos.getDetailLevel(pos));
		if (outputDetailLevel < DhSectionPos.getDetailLevel(pos))
		{
			throw new IllegalArgumentException("The output Detail Level [" + outputDetailLevel + "] is less than the input pos's detail level [" + DhSectionPos.getDetailLevel(pos) + "].");
		}



		// negative values need to be offset by the detail level difference squared (in blocks)
		// to skip over -0 (relative position) to -1 (relative position)
		int blockOffset = BitShiftUtil.powerOfTwo(detailLevelDifference) - 1;
		blockOffset = Math.max(1, blockOffset);

		int xInput = xInputOriginal;
		xInput += (xInputOriginal < 0) ? blockOffset : 0;

		int zInput = zInputOriginal;
		zInput += (zInputOriginal < 0) ? blockOffset : 0;

		// convert the input positions into the new detail level
		int xRelativePos = xInput / BitShiftUtil.powerOfTwo(detailLevelDifference);
		int zRelativePos = zInput / BitShiftUtil.powerOfTwo(detailLevelDifference);


		// width inclusive
		final int WIDTH_IN = FullDataSourceV2.WIDTH;
		// width exclusive
		final int WIDTH_EX = FullDataSourceV2.WIDTH - 1;

		// convert the positions into section relative space (0-63)
		xRelativePos = xInputOriginal >= 0 ? (xRelativePos % WIDTH_IN) : WIDTH_EX + (xRelativePos % WIDTH_IN);
		zRelativePos = zInputOriginal >= 0 ? (zRelativePos % WIDTH_IN) : WIDTH_EX + (zRelativePos % WIDTH_IN);

		return DhSectionPos.encode(outputDetailLevel, xRelativePos, zRelativePos);
	}

	//endregion



	//==================//
	// parent child pos //
	//==================//
	//region

	/**
	 * @return how many children this position has:
	 * 4 for 2D positions and cubic slabs, 8 for cubic cubes
	 */
	public static int getChildCount(long pos)
	{
		if (isCubic(pos) && cubicSplitsVertically(getDetailLevel(pos)))
		{
			return 8;
		}
		return 4;
	}

	/**
	 * Returns a position 1 detail level lower. <br><br>
	 *
	 * Relative child positions returned for each index: <br>
	 * 0 = (0,0) - North West <br>
	 * 1 = (1,0) - South West <br>
	 * 2 = (0,1) - North East <br>
	 * 3 = (1,1) - South East <br>
	 * Cubic positions that split vertically add 4 to reach the upper half (4-7).
	 *
	 * @param childIndex must be an int between 0 and {@link #getChildCount(long)} - 1
	 */
	public static long getChildByIndex(long pos, int childIndex) throws IllegalArgumentException, IllegalStateException
	{
		byte detailLevel = getDetailLevel(pos);
		int x = getX(pos);
		int z = getZ(pos);

		if (childIndex < 0 || childIndex >= getChildCount(pos))
		{
			throw new IllegalArgumentException("childIndex must be between 0 and "+(getChildCount(pos) - 1)+", given ["+childIndex+"].");
		}
		if (detailLevel <= 0)
		{
			throw new IllegalStateException("section detail must be greater than 0");
		}

		if (!isCubic(pos))
		{
			return DhSectionPos.encode((byte) (detailLevel - 1),
				x * 2 + (childIndex & 1),
				z * 2 + BitShiftUtil.half(childIndex & 2));
		}

		int y = getY(pos);
		int childY = cubicSplitsVertically(detailLevel)
			? (y * 2) + ((childIndex >> 2) & 1)
			: y;
		return DhSectionPos.encodeCubic((byte) (detailLevel - 1),
			x * 2 + (childIndex & 1),
			childY,
			z * 2 + BitShiftUtil.half(childIndex & 2));
	}
	/** Returns this position's child index in its parent */
	public static int getChildIndexOfParent(long pos)
	{
		int index = (getX(pos) & 1) + BitShiftUtil.square(getZ(pos) & 1);
		if (isCubic(pos)
			&& cubicSplitsVertically((byte) (getDetailLevel(pos) + 1)))
		{
			index += (getY(pos) & 1) << 2;
		}
		return index;
	}

	public static long getParentPos(long pos)
	{
		byte detailLevel = getDetailLevel(pos);
		if (!isCubic(pos))
		{
			return DhSectionPos.encode((byte) (detailLevel + 1), BitShiftUtil.half(getX(pos)), BitShiftUtil.half(getZ(pos)));
		}

		byte parentDetailLevel = (byte) (detailLevel + 1);
		int y = getY(pos);
		if (cubicSplitsVertically(parentDetailLevel))
		{
			y = BitShiftUtil.half(y);
		}
		return DhSectionPos.encodeCubic(parentDetailLevel, BitShiftUtil.half(getX(pos)), y, BitShiftUtil.half(getZ(pos)));
	}

	//endregion


	/**
	 * @throws IllegalArgumentException if given UP or DOWN for a 2D position,
	 * those directions only exist between cubic sections
	 */
	public static long getAdjacentPos(long pos, EDhDirection dir) throws IllegalArgumentException
	{
		if (dir == EDhDirection.UP || dir == EDhDirection.DOWN)
		{
			if (!isCubic(pos))
			{
				throw new IllegalArgumentException("getAdjacentPos can't be UP or DOWN, direction given: ["+dir.name()+"].");
			}

			return DhSectionPos.encodeCubic(getDetailLevel(pos),
				getX(pos),
				getY(pos) + dir.normal.y,
				getZ(pos));
		}

		if (isCubic(pos))
		{
			return DhSectionPos.encodeCubic(getDetailLevel(pos),
				getX(pos) + dir.normal.x,
				getY(pos),
				getZ(pos) + dir.normal.z);
		}

		return DhSectionPos.encode(getDetailLevel(pos),
				getX(pos) + dir.normal.x,
				getZ(pos) + dir.normal.z);
	}





	//=============//
	// comparisons //
	//=============//
	//region

	/**
	 * If both positions are cubic their vertical ranges are compared as well,
	 * otherwise only the horizontal area is compared.
	 */
	public static boolean contains(long aPos, long bPos)
	{
		int aMinX = getMinCornerBlockX(aPos);
		int aMinZ = getMinCornerBlockZ(aPos);

		int bMinX = getMinCornerBlockX(bPos);
		int bMinZ = getMinCornerBlockZ(bPos);

		int aBlockWidth = getBlockWidth(aPos) - 1; // minus 1 to account for zero based positional indexing
		int aMaxX = aMinX + aBlockWidth;
		int aMaxZ = aMinZ + aBlockWidth;

		boolean horizontallyContained =
			aMinX <= bMinX && bMinX <= aMaxX &&
			aMinZ <= bMinZ && bMinZ <= aMaxZ;
		if (!horizontallyContained
			|| !isCubic(aPos)
			|| !isCubic(bPos))
		{
			return horizontallyContained;
		}

		int aMinY = getCubicMinBlockY(aPos);
		int aMaxY = aMinY + getCubicBlockHeight(aPos) - 1;
		int bMinY = getCubicMinBlockY(bPos);
		return aMinY <= bMinY && bMinY <= aMaxY;
	}

	public static boolean contains(long aPos, DhBlockPos blockPos)
	{
		int sectionMinX = getMinCornerBlockX(aPos);
		int sectionMinZ = getMinCornerBlockZ(aPos);

		int blockX = blockPos.getX();
		int blockZ = blockPos.getZ();

		int sectionBlockWidth = getBlockWidth(aPos) - 1; // minus 1 to account for zero based positional indexing
		int sectionMaxX = sectionMinX + sectionBlockWidth;
		int sectionMaxZ = sectionMinZ + sectionBlockWidth;

		boolean horizontallyContained =
			sectionMinX <= blockX && blockX <= sectionMaxX &&
			sectionMinZ <= blockZ && blockZ <= sectionMaxZ;
		if (!horizontallyContained
			|| !isCubic(aPos))
		{
			return horizontallyContained;
		}

		int sectionMinY = getCubicMinBlockY(aPos);
		int blockY = blockPos.getY();
		return sectionMinY <= blockY && blockY < sectionMinY + getCubicBlockHeight(aPos);
	}

	//endregion



	//===========//
	// iterators //
	//===========//
	//region

	/** Applies the given consumer to all of this position's children. */
	public static void forEachChild(long pos, LongConsumer callback) throws IllegalArgumentException, IllegalStateException
	{
		int childCount = getChildCount(pos);
		for (int i = 0; i < childCount; i++)
		{
			callback.accept(getChildByIndex(pos, i));
		}
	}

	/** Applies the given consumer to all children of the position at the given section detail level. */
	public static void forEachChildDownToDetailLevel(long pos, byte minSectionDetailLevel, IPrimitiveLongConsumer callback) throws IllegalArgumentException, IllegalStateException
	{
		boolean stop = callback.accept(pos);
		if (stop || minSectionDetailLevel == getDetailLevel(pos))
		{
			return;
		}

		int childCount = getChildCount(pos);
		for (int i = 0; i < childCount; i++)
		{
			forEachChildDownToDetailLevel(getChildByIndex(pos, i), minSectionDetailLevel, callback);
		}
	}

	/** Applies the given consumer to all children of the position at the given section detail level. */
	public static void forEachChildAtDetailLevel(long pos, byte sectionDetailLevel, LongConsumer callback) throws IllegalArgumentException, IllegalStateException
	{
		if (sectionDetailLevel == getDetailLevel(pos))
		{
			callback.accept(pos);
			return;
		}

		int childCount = getChildCount(pos);
		for (int i = 0; i < childCount; i++)
		{
			forEachChildAtDetailLevel(getChildByIndex(pos, i), sectionDetailLevel, callback);
		}
	}

	/** Applies the given consumer to all children of the position at the given section detail level. */
	public static void forEachPosUpToDetailLevel(long pos, byte maxSectionDetailLevel, LongConsumer callback)
	{
		callback.accept(pos);
		if (maxSectionDetailLevel == getDetailLevel(pos))
		{
			return;
		}

		forEachPosUpToDetailLevel(getParentPos(pos), maxSectionDetailLevel, callback);
	}

	//endregion



	//==============//
	// Base methods //
	//==============//
	//region

	/** Example: "6*1,-3" for 2D positions, "6*1,2,-3" (x,y,z) for cubic positions */
	public static String toString(long pos)
	{
		if (isCubic(pos))
		{
			return getDetailLevel(pos) + "*" + getX(pos) + "," + getY(pos) + "," + getZ(pos);
		}
		return getDetailLevel(pos) + "*" + getX(pos) + "," + getZ(pos);
	}
	public static int hashCode(long pos) { return Long.hashCode(pos); }

	//endregion



	//================//
	// helper classes //
	//================//
	//region

	/** Used instead of {@link java.util.function.Function} to prevent unnecessary (un)wrapping. */
	@FunctionalInterface
	public interface IPrimitiveLongConsumer
	{
		boolean accept(long value);
	}

	//endregion


}
