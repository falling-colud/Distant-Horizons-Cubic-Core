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

package com.seibel.distanthorizons.api.methods.cubic;

import com.seibel.distanthorizons.api.interfaces.override.worldGenerator.IDhApiCubicWorldGenerator;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.objects.data.DhApiChunk;

/**
 * Cubic LODs: for levels a cubic chunks mod marks as cubic, LOD sections are cubes in an octree
 * instead of full height columns, so LODs lose detail vertically as well as horizontally and only
 * the part of a (possibly very tall) level near the camera is generated, stored and rendered. <br><br>
 *
 * A mod that makes levels cubic sets the hooks below once, early (before any level loads), and
 * registers an {@link IDhApiCubicWorldGenerator} for each cubic level to generate distant terrain.
 * Levels that aren't cubic are handled exactly as without this class. <br><br>
 *
 * Hooks are called from Distant Horizons' own threads and must be thread safe and fast.
 *
 * @since API 7.2.0 (cubic)
 */
public final class DhApiCubic
{
	/**
	 * Increases whenever cubic LOD support changes in a way a mod using it can notice.
	 * Its presence tells a cubic chunks mod that this Distant Horizons supports cubic LODs.
	 */
	public static final int VERSION = 1;



	/** Decides whether a level (a Minecraft Level object) has cubic LODs. */
	@FunctionalInterface
	public interface ICubicLevelPredicate
	{
		boolean isCubic(Object minecraftLevel);
	}

	/** How far above and below the camera the game itself renders terrain, in blocks. */
	@FunctionalInterface
	public interface IVerticalViewDistanceProvider
	{
		/** @return the distance in blocks, or a negative number if unknown */
		int getVerticalViewDistanceInBlocks(Object minecraftLevel);
	}

	/**
	 * Decides whether part of a chunk is complete: every block in it is final and lit.
	 * Only complete parts of a chunk replace LOD data.
	 */
	@FunctionalInterface
	public interface IChunkCompletenessPredicate
	{
		/**
		 * @param minecraftChunk the Minecraft ChunkAccess handed to Distant Horizons
		 * @param minBlockY the lowest block Y of the part
		 * @param maxBlockYExclusive one past the highest block Y of the part
		 */
		boolean isComplete(Object minecraftChunk, int minBlockY, int maxBlockYExclusive);
	}



	/** Implemented by Distant Horizons, see {@link #updateCubicChunk}. */
	public interface ICubicChunkUpdater
	{
		void updateCubicChunk(IDhApiLevelWrapper levelWrapper, DhApiChunk chunk);

		/** @see DhApiCubic#getPendingChunkUpdateCount() */
		int getPendingChunkUpdateCount();
	}



	private static volatile ICubicLevelPredicate cubicLevelPredicate = null;
	private static volatile ICubicChunkUpdater chunkUpdater = null;
	private static volatile IVerticalViewDistanceProvider verticalViewDistanceProvider = null;
	private static volatile IChunkCompletenessPredicate chunkCompletenessPredicate = null;



	private DhApiCubic() { }



	//=========//
	// setters //
	//=========//

	/**
	 * Sets which levels have cubic LODs. Must be set before the levels load,
	 * a level's LOD structure is chosen when Distant Horizons loads it.
	 */
	public static void setCubicLevelPredicate(ICubicLevelPredicate predicate) { cubicLevelPredicate = predicate; }

	/**
	 * Sets how far above and below the camera the game renders real terrain, so LODs don't
	 * draw over it there but do draw beyond it.
	 */
	public static void setVerticalViewDistanceProvider(IVerticalViewDistanceProvider provider) { verticalViewDistanceProvider = provider; }

	/** Sets which parts of loaded chunks are complete enough to replace LOD data. */
	public static void setChunkCompletenessPredicate(IChunkCompletenessPredicate predicate) { chunkCompletenessPredicate = predicate; }



	/** Distant Horizons internal, mods shouldn't use these. */
	public static final class Internal
	{
		private Internal() { }

		public static void setChunkUpdater(ICubicChunkUpdater updater) { chunkUpdater = updater; }
	}



	//=========//
	// getters //
	//=========//

	/** @return true if the given Minecraft level has cubic LODs */
	public static boolean isCubicLevel(Object minecraftLevel)
	{
		ICubicLevelPredicate predicate = cubicLevelPredicate;
		return predicate != null
			&& minecraftLevel != null
			&& predicate.isCubic(minecraftLevel);
	}

	/** @return the game's vertical view distance around the camera in blocks, or a negative number if unknown */
	public static int getVerticalViewDistanceInBlocks(Object minecraftLevel)
	{
		IVerticalViewDistanceProvider provider = verticalViewDistanceProvider;
		return (provider != null && minecraftLevel != null)
			? provider.getVerticalViewDistanceInBlocks(minecraftLevel)
			: -1;
	}

	/**
	 * Replaces the LODs of part of a chunk of a cubic level with real terrain. <br><br>
	 *
	 * Distant Horizons ignores its own chunk updates in cubic levels (a chunk column there is
	 * mostly unloaded cubes); the cubic chunks mod hands over the parts that are complete instead. <br>
	 * The chunk's {@link DhApiChunk#bottomYBlockPos} and {@link DhApiChunk#topYBlockPos} must be
	 * multiples of 64 (each 64 block tall part replaces that chunk's columns of one LOD section)
	 * and every column must cover that whole height with absolute Y positions, top to bottom,
	 * air included. A data point's sky and block light is the light directly above its top. <br><br>
	 *
	 * Thread safe, the work is done on Distant Horizons' threads. Does nothing for levels that aren't
	 * loaded or aren't cubic.
	 */
	public static void updateCubicChunk(IDhApiLevelWrapper levelWrapper, DhApiChunk chunk)
	{
		ICubicChunkUpdater updater = chunkUpdater;
		if (updater != null)
		{
			updater.updateCubicChunk(levelWrapper, chunk);
		}
	}

	/**
	 * How much of the handed over terrain Distant Horizons hasn't turned into LOD data yet:
	 * chunks waiting to be converted plus LOD writes waiting for the disk. <br>
	 * A mod handing over a lot of terrain should wait while this is high (a few hundred),
	 * Distant Horizons can't keep up then and anything handed over waits in memory.
	 */
	public static int getPendingChunkUpdateCount()
	{
		ICubicChunkUpdater updater = chunkUpdater;
		return (updater != null) ? updater.getPendingChunkUpdateCount() : 0;
	}

	/** @return true if the given part of the chunk may replace LOD data. True when no predicate is set. */
	public static boolean isChunkRangeComplete(Object minecraftChunk, int minBlockY, int maxBlockYExclusive)
	{
		IChunkCompletenessPredicate predicate = chunkCompletenessPredicate;
		return predicate == null
			|| predicate.isComplete(minecraftChunk, minBlockY, maxBlockYExclusive);
	}

}
