package com.seibel.distanthorizons.core.render.QuadTree;

import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos2D;
import com.seibel.distanthorizons.core.util.objects.quadTree.QuadNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;

/**
 * Holds all the data retrieved
 * while running {@link LodQuadTree#tryTick(DhBlockPos2D)}.
 * This allows for running different logic at different times for each node
 * based on whether it should be rendered and it's place in the tree.
 */
public class QuadTreeTickNodeHolder
{
	/** Nodes that should be pulled in from the disk */
	private final HashSet<LodRenderSection> sectionsToLoad = new HashSet<>();
	
	private final HashSet<QuadNode<LodRenderSection>> presentNodes = new HashSet<>();
	
	private final HashSet<QuadNode<LodRenderSection>> nodesToEnable = new HashSet<>();
	/** see {@link LodRenderSection#renderOctantMask}, only for enabled nodes drawn in place of some of their children */
	private final HashMap<QuadNode<LodRenderSection>, Integer> octantMaskByNode = new HashMap<>();
	private final HashSet<QuadNode<LodRenderSection>> nodesToDisable = new HashSet<>();
	private final ArrayList<QuadNode<LodRenderSection>> nodesToEnableDeleteChildrenList = new ArrayList<>();
	/** see {@link #addNodeNeedingData} */
	private final ArrayList<QuadNode<LodRenderSection>> nodesNeedingDataList = new ArrayList<>();

	/** 
	 * not included in {@link #clear()} to allow for use on the {@link LodQuadTree}'s
	 * queuing thread.
	 * Always generated based on other 
	 */
	private final ArrayList<QuadNode<LodRenderSection>> nodesForWorldGen = new ArrayList<>();
	
	private final QuadNodeNearComparator quadNodeNearComparator = new QuadNodeNearComparator();
	
	
	
	//=========//
	// methods //
	//=========//
	///region
	
	public void clear()
	{
		this.sectionsToLoad.clear();
		
		this.presentNodes.clear();
		
		this.nodesToEnable.clear();
		this.octantMaskByNode.clear();
		this.nodesToDisable.clear();
		this.nodesToEnableDeleteChildrenList.clear();
		this.nodesNeedingDataList.clear();
	}
	
	
	// loading
	public void addLoadSection(LodRenderSection section) { this.sectionsToLoad.add(section); }
	public HashSet<LodRenderSection> getLoadSections() { return this.sectionsToLoad; }
	
	
	// enable
	public void addEnableNode(QuadNode<LodRenderSection> node)
	{
		if(this.presentNodes.add(node))
		{
			// in James testing as of 4-21-2026
			// this should no longer be needed to prevent overlaps,
			// however I'm keeping it here as a quick fix solution if 
			// the problem comes up again
			if (false)
			{
				// not a big fan of having to check every node to prevent overlaps, but it does work
				this.nodesToEnable.removeIf((QuadNode<LodRenderSection> checkNode) ->
				{
					boolean contained = DhSectionPos.contains(node.sectionPos, checkNode.sectionPos);
					if (contained)
					{
						this.nodesToDisable.add(checkNode);
					}
					
					return contained;
				});
			}
			
			this.nodesToEnable.add(node);
		}
	}
	
	/** Cubic levels: enables a node that is drawn in place of the children not in {@code coveredChildMask}. */
	public void addEnableNode(QuadNode<LodRenderSection> node, int coveredChildMask)
	{
		this.addEnableNode(node);
		if (this.nodesToEnable.contains(node) && coveredChildMask != 0)
		{
			this.octantMaskByNode.put(node, coveredChildMask);
		}
	}

	/** @return the octant mask the node is enabled with, 0 if it's drawn whole */
	public int getOctantMask(QuadNode<LodRenderSection> node)
	{
		Integer mask = this.octantMaskByNode.get(node);
		return (mask != null) ? mask : 0;
	}

	/**  */
	public void removeEnableAndDisableNode(QuadNode<LodRenderSection> node)
	{
		this.nodesToEnable.remove(node);
		this.octantMaskByNode.remove(node);
		this.nodesToEnableDeleteChildrenList.remove(node);
		
		this.presentNodes.add(node); // should already be present, but re-added just in case
		this.nodesToDisable.add(node); // node shouldn't be rendered since it's being disabled by a parent
	}
	
	public HashSet<QuadNode<LodRenderSection>> getEnabledNodes() { return this.nodesToEnable; }
	
	
	// disable
	public void addDisableNode(QuadNode<LodRenderSection> node)
	{
		if(this.presentNodes.add(node))
		{
			this.nodesToDisable.add(node);
		}
	}
	public HashSet<QuadNode<LodRenderSection>> getDisableNodes() { return this.nodesToDisable; }
	
	
	// enable - delete children
	public void addEnableDeleteChildrenNode(QuadNode<LodRenderSection> node)
	{
		if(this.presentNodes.add(node))
		{
			this.nodesToEnableDeleteChildrenList.add(node);
		}
	}
	public ArrayList<QuadNode<LodRenderSection>> getEnableDeleteChildrenNodes() { return this.nodesToEnableDeleteChildrenList; }
	public ArrayList<QuadNode<LodRenderSection>> getWorldGenNodesNearToFar(DhBlockPos2D centerPos) { return this.getWorldGenNodesNearToFar(centerPos, 0); }
	/** @param centerBlockY only used for cubic positions */
	public ArrayList<QuadNode<LodRenderSection>> getWorldGenNodesNearToFar(DhBlockPos2D centerPos, int centerBlockY)
	{
		this.quadNodeNearComparator.centerPos = centerPos;
		this.quadNodeNearComparator.centerBlockY = centerBlockY;
		this.nodesToEnableDeleteChildrenList.sort(this.quadNodeNearComparator);
		this.nodesNeedingDataList.sort(this.quadNodeNearComparator);

		// this
		this.nodesForWorldGen.clear();
		this.nodesForWorldGen.addAll(this.nodesToEnableDeleteChildrenList);
		this.nodesForWorldGen.addAll(this.nodesNeedingDataList);
		this.nodesForWorldGen.sort(this.quadNodeNearComparator);

		return this.nodesForWorldGen;
	}

	/**
	 * Cubic levels: nodes that want to render but have no data yet.
	 * They don't render (their parent does), so they only get their data through world generation.
	 */
	public void addNodeNeedingData(QuadNode<LodRenderSection> node) { this.nodesNeedingDataList.add(node); }
	
	///endregion
	
	
	
	//================//
	// helper classes //
	//================//
	///region
	
	/** orders closest LODs first */
	private static class QuadNodeNearComparator implements Comparator<QuadNode<LodRenderSection>>
	{
		public DhBlockPos2D centerPos = DhBlockPos2D.ZERO;
		/** only used for cubic positions */
		public int centerBlockY = 0;

		@Override
		public int compare(QuadNode<LodRenderSection> nodeA, QuadNode<LodRenderSection> nodeB)
		{
			// closer LODs first
			long aDist = this.distance(nodeA.sectionPos);
			long bDist = this.distance(nodeB.sectionPos);
			return Long.compare(aDist, bDist); // smaller numbers first
		}
		private long distance(long pos)
		{
			long distance = DhSectionPos.getManhattanBlockDistance(pos, this.centerPos);
			if (DhSectionPos.isCubic(pos))
			{
				distance += Math.abs((long) DhSectionPos.getCubicCenterBlockY(pos) - this.centerBlockY);
			}
			return distance;
		}
	}
	
	///endregion
	
	
	
}
