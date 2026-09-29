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

package com.seibel.distanthorizons.core.api.internal.chunkUpdating;

import com.seibel.distanthorizons.api.enums.config.EDhApiWorldCompressionMode;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGenerationStep;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.methods.cubic.DhApiCubic;
import com.seibel.distanthorizons.api.objects.data.DhApiChunk;
import com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint;
import com.seibel.distanthorizons.core.api.internal.SharedApi;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.dataObjects.transformers.LodDataBuilder;
import com.seibel.distanthorizons.core.level.AbstractDhLevel;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.util.threading.PriorityTaskPicker;
import com.seibel.distanthorizons.core.util.threading.ThreadPoolUtil;
import com.seibel.distanthorizons.core.world.AbstractDhWorld;
import com.seibel.distanthorizons.core.world.DhApiWorldProxy;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import it.unimi.dsi.fastutil.longs.LongArrayList;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Turns real terrain handed over through {@link DhApiCubic#updateCubicChunk} into
 * cubic leaf sections (64 x 64 x 64 blocks, one chunk's 16 x 16 columns at a time)
 * and queues them for saving. Updates of the same section are merged in memory first,
 * see {@link AbstractDhLevel#queueCubicChunkDataSource}. <br><br>
 *
 * Updates of the same part of a chunk are applied one after another in the order they were
 * handed over, and one waiting to be applied is replaced by a newer one, so a quick second
 * update can never be overtaken by the older one on another thread.
 */
public class CubicChunkUpdater implements DhApiCubic.ICubicChunkUpdater
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();

	public static final CubicChunkUpdater INSTANCE = new CubicChunkUpdater();

	/** how tall a cubic leaf section is */
	private static final int LEAF_HEIGHT = DhSectionPos.getCubicBlockHeight(DhSectionPos.SECTION_BLOCK_DETAIL_LEVEL);

	private static final AtomicBoolean INVALID_RANGE_LOGGED = new AtomicBoolean(false);

	/** chunks handed over and not yet turned into LOD data */
	private final AtomicInteger buildingCount = new AtomicInteger(0);

	/** the chunk parts waiting to be applied or being applied, guarded by itself */
	private final HashMap<PartKey, Slot> slotByPart = new HashMap<>();

	/** one part of a chunk of one level */
	private static final class PartKey
	{
		private final IDhLevel level;
		private final int chunkX;
		private final int chunkZ;
		private final int bottomY;
		private final int topY;

		PartKey(IDhLevel level, DhApiChunk chunk)
		{
			this.level = level;
			this.chunkX = chunk.chunkPosX;
			this.chunkZ = chunk.chunkPosZ;
			this.bottomY = chunk.bottomYBlockPos;
			this.topY = chunk.topYBlockPos;
		}

		@Override
		public boolean equals(Object obj)
		{
			if (!(obj instanceof PartKey))
			{
				return false;
			}
			PartKey other = (PartKey) obj;
			return this.level == other.level
				&& this.chunkX == other.chunkX
				&& this.chunkZ == other.chunkZ
				&& this.bottomY == other.bottomY
				&& this.topY == other.topY;
		}

		@Override
		public int hashCode() { return Objects.hash(System.identityHashCode(this.level), this.chunkX, this.chunkZ, this.bottomY, this.topY); }
	}

	private static final class Slot
	{
		/** the newest update not applied yet, null if none */
		DhApiChunk next;
	}



	private CubicChunkUpdater() { }



	@Override
	public void updateCubicChunk(IDhApiLevelWrapper apiLevelWrapper, DhApiChunk chunk)
	{
		if (!(apiLevelWrapper instanceof ILevelWrapper)
			|| chunk == null)
		{
			return;
		}

		AbstractDhWorld dhWorld = SharedApi.getAbstractDhWorld();
		if (dhWorld == null
			|| DhApiWorldProxy.INSTANCE.tryGetReadOnly())
		{
			return;
		}

		IDhLevel dhLevel = dhWorld.getLevel((ILevelWrapper) apiLevelWrapper);
		if (!(dhLevel instanceof AbstractDhLevel)
			|| !dhLevel.isCubic())
		{
			return;
		}

		if (Math.floorMod(chunk.bottomYBlockPos, LEAF_HEIGHT) != 0
			|| Math.floorMod(chunk.topYBlockPos, LEAF_HEIGHT) != 0
			|| chunk.topYBlockPos <= chunk.bottomYBlockPos)
		{
			if (INVALID_RANGE_LOGGED.compareAndSet(false, true))
			{
				LOGGER.warn("Cubic chunk updates must cover whole "+LEAF_HEIGHT+" block tall sections, chunk ["+chunk.chunkPosX+","+chunk.chunkPosZ+"] covers Y ["+chunk.bottomYBlockPos+","+chunk.topYBlockPos+"). Further errors won't be logged.");
			}
			return;
		}

		AbstractExecutorService executor = ThreadPoolUtil.getChunkToLodBuilderExecutor();
		if (executor == null)
		{
			return;
		}

		PartKey key = new PartKey(dhLevel, chunk);
		boolean startApplying;
		synchronized (this.slotByPart)
		{
			Slot slot = this.slotByPart.get(key);
			startApplying = (slot == null);
			if (startApplying)
			{
				slot = new Slot();
				this.slotByPart.put(key, slot);
			}

			if (slot.next == null)
			{
				this.buildingCount.incrementAndGet();
			}
			// an update still waiting is replaced, the newer one covers the same blocks
			slot.next = chunk;
		}

		if (!startApplying)
		{
			// the task applying this part picks it up once it's done with the one before
			return;
		}

		try
		{
			executor.execute(() -> this.applyInOrder((AbstractDhLevel) dhLevel, key));
		}
		catch (RejectedExecutionException ignore)
		{
			// the thread pool is being resized or shut down, the chunk mod hands the chunk over again when it changes
			synchronized (this.slotByPart)
			{
				Slot slot = this.slotByPart.remove(key);
				if (slot != null && slot.next != null)
				{
					this.buildingCount.decrementAndGet();
				}
			}
		}
	}

	/** Applies the part's updates one after another until none is waiting. */
	private void applyInOrder(AbstractDhLevel dhLevel, PartKey key)
	{
		while (true)
		{
			DhApiChunk chunk;
			synchronized (this.slotByPart)
			{
				Slot slot = this.slotByPart.get(key);
				if (slot == null)
				{
					return;
				}

				chunk = slot.next;
				slot.next = null;
				if (chunk == null)
				{
					this.slotByPart.remove(key);
					return;
				}
			}

			try
			{
				buildAndQueue(dhLevel, chunk);
			}
			finally
			{
				this.buildingCount.decrementAndGet();
			}
		}
	}

	@Override
	public int getPendingChunkUpdateCount()
	{
		// the built sections are saved through the file handler,
		// when it falls behind they pile up in memory
		PriorityTaskPicker.Executor fileHandler = ThreadPoolUtil.getFileHandlerExecutor();
		int fileQueue = (fileHandler != null) ? fileHandler.getQueueSize() : 0;
		return this.buildingCount.get() + fileQueue;
	}

	private static void buildAndQueue(AbstractDhLevel dhLevel, DhApiChunk chunk)
	{
		try
		{
			int sectionX = Math.floorDiv(chunk.chunkPosX, FullDataSourceV2.NUMB_OF_CHUNKS_WIDE);
			int sectionZ = Math.floorDiv(chunk.chunkPosZ, FullDataSourceV2.NUMB_OF_CHUNKS_WIDE);
			int columnOffsetX = Math.floorMod(chunk.chunkPosX, FullDataSourceV2.NUMB_OF_CHUNKS_WIDE) * LodUtil.CHUNK_WIDTH;
			int columnOffsetZ = Math.floorMod(chunk.chunkPosZ, FullDataSourceV2.NUMB_OF_CHUNKS_WIDE) * LodUtil.CHUNK_WIDTH;

			// sort each column top to bottom once
			for (int x = 0; x < LodUtil.CHUNK_WIDTH; x++)
			{
				for (int z = 0; z < LodUtil.CHUNK_WIDTH; z++)
				{
					List<DhApiTerrainDataPoint> column = chunk.getDataPoints(x, z);
					if (column != null)
					{
						LodDataBuilder.putListInTopDownOrder(column);
					}
				}
			}

			ArrayList<DhApiTerrainDataPoint> clippedColumn = new ArrayList<>();
			for (int minY = chunk.bottomYBlockPos; minY < chunk.topYBlockPos; minY += LEAF_HEIGHT)
			{
				int maxY = minY + LEAF_HEIGHT;
				long pos = DhSectionPos.encodeCubic(DhSectionPos.SECTION_BLOCK_DETAIL_LEVEL, sectionX, Math.floorDiv(minY, LEAF_HEIGHT), sectionZ);

				try (FullDataSourceV2 dataSource = FullDataSourceV2.createEmpty(pos))
				{
					// real terrain always propagates up
					dataSource.applyToParent = true;

					boolean anyColumn = false;
					for (int x = 0; x < LodUtil.CHUNK_WIDTH; x++)
					{
						for (int z = 0; z < LodUtil.CHUNK_WIDTH; z++)
						{
							List<DhApiTerrainDataPoint> column = chunk.getDataPoints(x, z);
							if (column == null
								|| !clipColumn(column, minY, maxY, clippedColumn))
							{
								// incomplete columns never replace LOD data
								continue;
							}

							LongArrayList packed = LodDataBuilder.convertApiDataPointListToPackedLongArray(clippedColumn, dataSource, minY, false);
							dataSource.setSingleColumn(packed, columnOffsetX + x, columnOffsetZ + z, EDhApiWorldGenerationStep.LIGHT, EDhApiWorldCompressionMode.MERGE_SAME_BLOCKS);
							anyColumn = true;
						}
					}

					if (anyColumn)
					{
						dataSource.isEmpty = false;
						dhLevel.queueCubicChunkDataSource(dataSource);
					}
				}
			}
		}
		catch (Exception e)
		{
			LOGGER.error("Unable to turn cubic chunk ["+chunk.chunkPosX+","+chunk.chunkPosZ+"] Y ["+chunk.bottomYBlockPos+","+chunk.topYBlockPos+") into LOD data, error: ["+e.getMessage()+"].", e);
		}
	}

	/**
	 * Fills the output with the column's data points inside [minY, maxY), top to bottom.
	 * @return false if the column doesn't cover that whole range
	 */
	private static boolean clipColumn(List<DhApiTerrainDataPoint> topDownColumn, int minY, int maxY, ArrayList<DhApiTerrainDataPoint> output)
	{
		output.clear();
		int expectedTop = maxY;
		for (int i = 0; i < topDownColumn.size(); i++)
		{
			DhApiTerrainDataPoint point = topDownColumn.get(i);
			int top = Math.min(point.topYBlockPos, maxY);
			int bottom = Math.max(point.bottomYBlockPos, minY);
			if (top <= bottom)
			{
				if (point.topYBlockPos <= minY)
				{
					// below the range, everything after this is too
					break;
				}
				continue;
			}

			if (top != expectedTop)
			{
				// a gap
				return false;
			}

			output.add((top == point.topYBlockPos && bottom == point.bottomYBlockPos)
				? point
				: DhApiTerrainDataPoint.create(point.detailLevel, point.blockLightLevel, point.skyLightLevel, bottom, top, point.blockStateWrapper, point.biomeWrapper));
			expectedTop = bottom;
		}

		return expectedTop == minY && !output.isEmpty();
	}

}
