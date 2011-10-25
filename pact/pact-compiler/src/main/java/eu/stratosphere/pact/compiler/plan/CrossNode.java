/***********************************************************************************************************************
 *
 * Copyright (C) 2010 by the Stratosphere project (http://stratosphere.eu)
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 **********************************************************************************************************************/

package eu.stratosphere.pact.compiler.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import eu.stratosphere.nephele.configuration.Configuration;
import eu.stratosphere.pact.common.contract.CompilerHints;
import eu.stratosphere.pact.common.contract.Contract;
import eu.stratosphere.pact.common.contract.CrossContract;
import eu.stratosphere.pact.common.contract.Order;
import eu.stratosphere.pact.compiler.CompilerException;
import eu.stratosphere.pact.compiler.DataStatistics;
import eu.stratosphere.pact.compiler.GlobalProperties;
import eu.stratosphere.pact.compiler.LocalProperties;
import eu.stratosphere.pact.compiler.OutputContract;
import eu.stratosphere.pact.compiler.PactCompiler;
import eu.stratosphere.pact.compiler.costs.CostEstimator;
import eu.stratosphere.pact.runtime.task.util.OutputEmitter.ShipStrategy;
import eu.stratosphere.pact.runtime.task.util.TaskConfig.LocalStrategy;

/**
 * The Optimizer representation of a <i>Cross</i> contract node.
 * 
 * @author Stephan Ewen (stephan.ewen@tu-berlin.de)
 */
public class CrossNode extends TwoInputNode {

	private List<CrossNode> cachedPlans; // a cache for the computed alternative plans

	/**
	 * Creates a new CrossNode for the given contract.
	 * 
	 * @param pactContract
	 *        The Cross contract object.
	 */
	public CrossNode(CrossContract<?, ?, ?, ?, ?, ?> pactContract) {
		super(pactContract);

		Configuration conf = getPactContract().getParameters();
		String localStrategy = conf.getString(PactCompiler.HINT_LOCAL_STRATEGY, null);

		if (localStrategy != null) {
			if (PactCompiler.HINT_LOCAL_STRATEGY_NESTEDLOOP_BLOCKED_OUTER_FIRST.equals(localStrategy)) {
				setLocalStrategy(LocalStrategy.NESTEDLOOP_BLOCKED_OUTER_FIRST);
			} else if (PactCompiler.HINT_LOCAL_STRATEGY_NESTEDLOOP_BLOCKED_OUTER_SECOND.equals(localStrategy)) {
				setLocalStrategy(LocalStrategy.NESTEDLOOP_BLOCKED_OUTER_SECOND);
			} else if (PactCompiler.HINT_LOCAL_STRATEGY_NESTEDLOOP_STREAMED_OUTER_FIRST.equals(localStrategy)) {
				setLocalStrategy(LocalStrategy.NESTEDLOOP_STREAMED_OUTER_FIRST);
			} else if (PactCompiler.HINT_LOCAL_STRATEGY_NESTEDLOOP_STREAMED_OUTER_SECOND.equals(localStrategy)) {
				setLocalStrategy(LocalStrategy.NESTEDLOOP_STREAMED_OUTER_SECOND);
			} else {
				throw new CompilerException("Invalid local strategy hint for cross contract: " + localStrategy);
			}
		} else {
			setLocalStrategy(LocalStrategy.NONE);
		}

	}

	/**
	 * Copy constructor to create a copy of a node with different predecessors. The predecessors
	 * is assumed to be of the same type as in the template node and merely copies with different
	 * strategies, as they are created in the process of the plan enumeration.
	 * 
	 * @param template
	 *        The node to create a copy of.
	 * @param pred1
	 *        The new predecessor for the first input.
	 * @param pred2
	 *        The new predecessor for the second input.
	 * @param conn1
	 *        The old connection of the first input to copy properties from.
	 * @param conn2
	 *        The old connection of the second input to copy properties from.
	 * @param globalProps
	 *        The global properties of this copy.
	 * @param localProps
	 *        The local properties of this copy.
	 */
	protected CrossNode(CrossNode template, List<OptimizerNode> pred1, List<OptimizerNode> pred2, List<PactConnection> conn1,
			List<PactConnection> conn2, GlobalProperties globalProps, LocalProperties localProps) {
		super(template, pred1, pred2, conn1, conn2, globalProps, localProps);
	}

	// ------------------------------------------------------------------------

	/**
	 * Gets the contract object for this Cross node.
	 * 
	 * @return The contract.
	 */
	@Override
	public CrossContract<?, ?, ?, ?, ?, ?> getPactContract() {
		return (CrossContract<?, ?, ?, ?, ?, ?>) super.getPactContract();
	}

	/*
	 * (non-Javadoc)
	 * @see eu.stratosphere.pact.compiler.plan.OptimizerNode#getName()
	 */
	@Override
	public String getName() {
		return "Cross";
	}

	/*
	 * (non-Javadoc)
	 * @see eu.stratosphere.pact.compiler.plan.OptimizerNode#isMemoryConsumer()
	 */
	@Override
	public int getMemoryConsumerCount() {
		switch(this.localStrategy) {
			case NESTEDLOOP_BLOCKED_OUTER_FIRST:   return 1;
			case NESTEDLOOP_BLOCKED_OUTER_SECOND:  return 1;
			case NESTEDLOOP_STREAMED_OUTER_FIRST:  return 1;
			case NESTEDLOOP_STREAMED_OUTER_SECOND: return 1;
			default:	                           return 0;
		}
	}

	/*
	 * (non-Javadoc)
	 * @see eu.stratosphere.pact.compiler.plan.OptimizerNode#setInputs(java.util.Map)
	 */
	@Override
	public void setInputs(Map<Contract, OptimizerNode> contractToNode) {
		// call the super function that sets the connection according to the hints
		super.setInputs(contractToNode);

		ShipStrategy firstSS = this.input1.get(0).getShipStrategy();
		for(PactConnection c : this.input1) {
			if(c.getShipStrategy() != firstSS)
				throw new CompilerException("Invalid specification of fixed shipping strategies for first input of Cross contract '"
						+ getPactContract().getName() + "' (all shipping strategies must be equal of a single input).");
				
		}

		ShipStrategy secondSS = this.input2.get(0).getShipStrategy();
		for(PactConnection c : this.input2) {
			if(c.getShipStrategy() != secondSS)
				throw new CompilerException("Invalid specification of fixed shipping strategies for second input of Cross contract '"
						+ getPactContract().getName() + "' (all shipping strategies must be equal of a single input).");
				
		}

		// check if only one connection is fixed and adjust the other to the corresponding value
		List<PactConnection> other = null;
		List<PactConnection> toAdjust = null;

		if (firstSS != ShipStrategy.NONE) {
			if (secondSS == ShipStrategy.NONE) {
				// first is fixed, second variable
				toAdjust = this.input2;
				other = this.input1;
			} else {
				// both are fixed. check if in a valid way
				if (!((firstSS == ShipStrategy.BROADCAST && secondSS == ShipStrategy.FORWARD)
					|| (firstSS == ShipStrategy.FORWARD && secondSS == ShipStrategy.BROADCAST)
					|| (firstSS == ShipStrategy.SFR && secondSS == ShipStrategy.SFR))) {
					throw new CompilerException("Invalid combination of fixed shipping strategies for Cross contract '"
						+ getPactContract().getName() + "'.");
				}
			}
		} else if (secondSS != ShipStrategy.NONE) {
			//firstSS == NONE
			
			// second is fixed, first is variable
			toAdjust = this.input1;
			other = this.input2;
		}

		if (toAdjust != null) {
			// other cann't be null here
			if (other.get(0).getShipStrategy() == ShipStrategy.BROADCAST) {
				for(PactConnection c : toAdjust)
					c.setShipStrategy(ShipStrategy.FORWARD);
			} else if (other.get(0).getShipStrategy() == ShipStrategy.FORWARD) {
				for(PactConnection c : toAdjust)
					c.setShipStrategy(ShipStrategy.BROADCAST);
			} else if (other.get(0).getShipStrategy() == ShipStrategy.SFR) {
				for(PactConnection c : toAdjust)
					c.setShipStrategy(ShipStrategy.SFR);
			} else {
				throw new CompilerException("Invalid shipping strategy for Cross contract '"
					+ getPactContract().getName() + "': " + other.get(0).getShipStrategy());
			}
		}
	}

	/*
	 * (non-Javadoc)
	 * @see eu.stratosphere.pact.compiler.plan.OptimizerNode#computeInterestingProperties()
	 */
	@Override
	public void computeInterestingPropertiesForInputs(CostEstimator estimator) {
		// the cross itself has no interesting properties.
		// check, if there is an output contract that tells us that certain properties are preserved.
		// if so, propagate to the child.
		List<InterestingProperties> thisNodesIntProps = getInterestingProperties();
		List<InterestingProperties> props = null;

		switch (getOutputContract()) {
		case SameKeyFirst:
		case SuperKeyFirst:
			props = InterestingProperties.filterByOutputContract(thisNodesIntProps, getOutputContract());
			for(PactConnection c : this.input1) {
				if (!props.isEmpty()) {
					c.addAllInterestingProperties(props);
				} else {
					c.setNoInterestingProperties();
				}
			}
			break;

		case SameKeySecond:
		case SuperKeySecond:
			props = InterestingProperties.filterByOutputContract(thisNodesIntProps, getOutputContract());
			for(PactConnection c : this.input2) {
				if (!props.isEmpty()) {
					c.addAllInterestingProperties(props);
				} else {
					c.setNoInterestingProperties();
				}
			}
			break;

		default:
			for(PactConnection c : this.input1)
				c.setNoInterestingProperties();
			for(PactConnection c : this.input2)
				c.setNoInterestingProperties();
			break;
		}
	}

	/*
	 * (non-Javadoc)
	 * @see
	 * eu.stratosphere.pact.compiler.plan.OptimizerNode#getAlternativePlans(eu.stratosphere.pact
	 * .compiler.costs.CostEstimator)
	 */
	@Override
	public List<CrossNode> getAlternativePlans(CostEstimator estimator) {
		// check if we have a cached version
		if (this.cachedPlans != null) {
			return this.cachedPlans;
		}

//		List<CrossNode> outputPlans = new ArrayList<CrossNode>();
//		getAlternativePlansRecursively(new ArrayList<OptimizerNode>(0), new ArrayList<OptimizerNode>(0), estimator, outputPlans);

		
		// TODO: mjsax
		// right now we do not enumerate all plans
		// -> because of union we have to do a recursive enumeration, what is missing right now
		List<OptimizerNode> allPreds1 = new ArrayList<OptimizerNode>(this.input1.size());
		for(PactConnection c : this.input1) {
			allPreds1.add(c.getSourcePact());
		}
		
		// TODO: mjsax
		// right now we do not enumerate all plans
		// -> because of union we have to do a recursive enumeration, what is missing right now
		List<OptimizerNode> allPreds2 = new ArrayList<OptimizerNode>(this.input2.size());
		for(PactConnection c : this.input2) {
			allPreds2.add(c.getSourcePact());
		}

		

		List<CrossNode> outputPlans = new ArrayList<CrossNode>();
		for(PactConnection c : this.input1) {
			List<? extends OptimizerNode> inPlans1 = c.getSourcePact().getAlternativePlans(estimator);
			
			for(PactConnection cc : this.input2) {
				List<? extends OptimizerNode> inPlans2 = cc.getSourcePact().getAlternativePlans(estimator);

				// go over each combination of alternative children from the two inputs
				for (OptimizerNode pred1 : inPlans1) {
					for (OptimizerNode pred2 : inPlans2) {
						// check, whether the two children have the same
						// sub-plan in the common part before the branches
						if (!areBranchCompatible(pred1, pred2)) {
							continue;
						}
		
						ShipStrategy ss1 = c.getShipStrategy();
						ShipStrategy ss2 = cc.getShipStrategy();
		
						if (ss1 != ShipStrategy.NONE) {
							// if one is fixed, the other is also
							createLocalAlternatives(outputPlans, pred1, pred2, ss1, ss2, estimator, allPreds1, allPreds2);
						} else {
							// create all alternatives
							createLocalAlternatives(outputPlans, pred1, pred2, ShipStrategy.BROADCAST, ShipStrategy.FORWARD,
								estimator, allPreds1, allPreds2);
							createLocalAlternatives(outputPlans, pred1, pred2, ShipStrategy.FORWARD, ShipStrategy.BROADCAST,
								estimator, allPreds1, allPreds2);
						}
					}
				}
			}
		}

		// check if the list does not contain any plan. That may happen, if the channels specify
		// incompatible shipping strategies.
		if (outputPlans.isEmpty()) {
			throw new CompilerException("Could not create a valid plan for the cross contract '"
				+ getPactContract().getName() + "'. The compiler hints specified incompatible shipping strategies.");
		}

		// prune the plans
		prunePlanAlternatives(outputPlans);

		// cache the result only if we have multiple outputs --> this function gets invoked multiple times
		if (isBranching()) {
			this.cachedPlans = outputPlans;
		}

		return outputPlans;
	}

	// build left alternative recursively
	private void getAlternativePlansRecursively(List<OptimizerNode> allLeftPreds, List<OptimizerNode> allRightPreds, CostEstimator estimator, List<CrossNode> outputPlans) {
		// what is our left recursive depth
		final int allLeftPredsSize = allLeftPreds.size();
		// pick the connection this recursive step has to process
		PactConnection leftConnToProcess = this.input1.get(allLeftPredsSize);
		// get all alternatives for current recursion level
		List<? extends OptimizerNode> inPlansLeft = leftConnToProcess.getSourcePact().getAlternativePlans(estimator);
		
		// now enumerate all alternative of this recursion level
		for (OptimizerNode pred : inPlansLeft) {
			// add an alternative plan node
			allLeftPreds.add(pred);

			// check if the hit the last recursion level
			if(allLeftPredsSize + 1 == this.input1.size()) {
				// last left recursion level: left start with right side
				getAlternativePlansRecursively2(allLeftPreds, allRightPreds, estimator, outputPlans);
			} else {
				getAlternativePlansRecursively(allLeftPreds, allRightPreds, estimator, outputPlans);
			}
			
			// remove the added alternative plan node, in order to replace it with the next alternative at the beginning of the loop
			allLeftPreds.remove(allLeftPredsSize);
		}

	}

	// build left alternative recursively
	private void getAlternativePlansRecursively2(List<OptimizerNode> allLeftPreds, List<OptimizerNode> allRightPreds, CostEstimator estimator, List<CrossNode> outputPlans) {
		// what is our right recursive depth
		final int allRightPredsSize = allRightPreds.size();
		// pick the connection this recursive step has to process
		PactConnection rightConnToProcess = this.input2.get(allRightPredsSize);
		// get all alternatives for current recursion level
		List<? extends OptimizerNode> inPlansRight = rightConnToProcess.getSourcePact().getAlternativePlans(estimator);
		
		// now enumerate all alternative of this recursion level
		for (OptimizerNode pred : inPlansRight) {
			// add an alternative plan node
			allRightPreds.add(pred);
			
			// check if the hit the last recursion level
			if(allRightPredsSize + 1 == this.input2.size()) {
				// last right recursion level: now we can build an alternative Cross node
				
				// TODO
			} else {
				getAlternativePlansRecursively2(allLeftPreds, allRightPreds, estimator, outputPlans);
			}
			
			// remove the added alternative plan node, in order to replace it with the next alternative at the beginning of the loop
			allRightPreds.remove(allRightPredsSize);
		}
	}

	
	
	/**
	 * Private utility method that generates the alternative Cross nodes, given fixed shipping strategies
	 * for the inputs.
	 * 
	 * @param target
	 *        The list to put the alternatives in.
	 * @param pred1
	 *        The predecessor node for the first input.
	 * @param pred2
	 *        The predecessor node for the second input.
	 * @param ss1
	 *        The shipping strategy for the first input.
	 * @param ss2
	 *        The shipping strategy for the second input.
	 * @param estimator
	 *        The cost estimator.
	 */
	private void createLocalAlternatives(List<CrossNode> target, OptimizerNode pred1, OptimizerNode pred2,
			ShipStrategy ss1, ShipStrategy ss2, CostEstimator estimator, List<OptimizerNode> allPreds1, List<OptimizerNode> allPreds2) {
		// compute the given properties of the incoming data
		LocalProperties lpDefaults = new LocalProperties();

		GlobalProperties gp = null;
		LocalProperties lp = null;

		OutputContract oc = getOutputContract();

		boolean isFirst = false;

		if (oc.appliesToFirstInput()) {
			gp = PactConnection.getGlobalPropertiesAfterConnection(pred1, this, ss1);
			lp = PactConnection.getLocalPropertiesAfterConnection(pred1, this, ss1);
			isFirst = true;
		} else if (oc.appliesToSecondInput()) {
			gp = PactConnection.getGlobalPropertiesAfterConnection(pred2, this, ss2);
			lp = PactConnection.getLocalPropertiesAfterConnection(pred2, this, ss2);
		} else {
			gp = new GlobalProperties();
			lp = new LocalProperties();
		}

		gp.setKeyUnique(false);
		lp.setKeyUnique(false);

		GlobalProperties gpNoOrder = gp.createCopy();
		gpNoOrder.setKeyOrder(Order.NONE);

		// for the streamed nested loop strategies, the local properties (except uniqueness) of the
		// outer side are preserved

		// create alternatives for different local strategies
		LocalStrategy ls = getLocalStrategy();

		if (ls != LocalStrategy.NONE) {
			// local strategy is fixed
			if (ls == LocalStrategy.NESTEDLOOP_STREAMED_OUTER_FIRST) {
				gp = isFirst ? gp : gpNoOrder;
				lp = isFirst ? lp : lpDefaults;
			} else if (ls == LocalStrategy.NESTEDLOOP_STREAMED_OUTER_SECOND) {
				gp = isFirst ? gpNoOrder : gp;
				lp = isFirst ? lpDefaults : lp;
			} else if (ls == LocalStrategy.NESTEDLOOP_BLOCKED_OUTER_FIRST
				|| ls == LocalStrategy.NESTEDLOOP_BLOCKED_OUTER_SECOND) {
				gp = gpNoOrder;
				lp = lpDefaults;
			} else {
				// not valid
				return;
			}

			createCrossAlternative(target, pred1, pred2, ss1, ss2, ls, gp, lp, estimator, allPreds1, allPreds2);
		}
		else {
			// we generate the streamed nested-loops only, when we have size estimates. otherwise, we generate
			// only the block nested-loops variants, as they are more robust.
			if (pred1.getEstimatedOutputSize() > 0 && pred2.getEstimatedOutputSize() > 0) {
				if (isFirst) {
					createCrossAlternative(target, pred1, pred2, ss1, ss2, LocalStrategy.NESTEDLOOP_STREAMED_OUTER_FIRST,
						gp, lp, estimator, allPreds1, allPreds2);
					createCrossAlternative(target, pred1, pred2, ss1, ss2, LocalStrategy.NESTEDLOOP_STREAMED_OUTER_SECOND,
						gpNoOrder.createCopy(), lpDefaults.createCopy(), estimator, allPreds1, allPreds2);
				} else {
					createCrossAlternative(target, pred1, pred2, ss1, ss2, LocalStrategy.NESTEDLOOP_STREAMED_OUTER_SECOND,
						gp, lp, estimator, allPreds1, allPreds2);
					createCrossAlternative(target, pred1, pred2, ss1, ss2, LocalStrategy.NESTEDLOOP_STREAMED_OUTER_FIRST,
						gpNoOrder.createCopy(), lpDefaults.createCopy(), estimator, allPreds1, allPreds2);
				}
			}

			createCrossAlternative(target, pred1, pred2, ss1, ss2, LocalStrategy.NESTEDLOOP_BLOCKED_OUTER_FIRST,
				gpNoOrder.createCopy(), lpDefaults.createCopy(), estimator, allPreds1, allPreds2);
			createCrossAlternative(target, pred1, pred2, ss1, ss2, LocalStrategy.NESTEDLOOP_BLOCKED_OUTER_SECOND,
				gpNoOrder, lpDefaults, estimator, allPreds1, allPreds2);
		}
	}

	/**
	 * Private utility method that generates a candidate Cross node, given fixed shipping strategies and a fixed
	 * local strategy.
	 * 
	 * @param target
	 *        The list to put the alternatives in.
	 * @param pred1
	 *        The predecessor node for the first input.
	 * @param pred2
	 *        The predecessor node for the second input.
	 * @param ss1
	 *        The shipping strategy for the first input.
	 * @param ss2
	 *        The shipping strategy for the second input.
	 * @param ls
	 *        The local strategy.
	 * @param outGp
	 *        The global properties of the data that goes to the user function.
	 * @param outLp
	 *        The local properties of the data that goes to the user function.
	 * @param estimator
	 *        The cost estimator.
	 */
	private void createCrossAlternative(List<CrossNode> target, OptimizerNode pred1, OptimizerNode pred2,
			ShipStrategy ss1, ShipStrategy ss2, LocalStrategy ls, GlobalProperties outGp, LocalProperties outLp,
			CostEstimator estimator, List<OptimizerNode> allPreds1, List<OptimizerNode> allPreds2) {
		// create a new reduce node for this input
		CrossNode n = new CrossNode(this, allPreds1, allPreds2, this.input1, this.input2, outGp, outLp);
		for(PactConnection c : n.input1)
			c.setShipStrategy(ss1);
		for(PactConnection c : n.input2)
			c.setShipStrategy(ss2);
		n.setLocalStrategy(ls);

		// compute, which of the properties survive, depending on the output contract
		n.getGlobalProperties().filterByOutputContract(getOutputContract());
		n.getLocalProperties().filterByOutputContract(getOutputContract());

		// compute the costs
		estimator.costOperator(n);

		target.add(n);
	}
	
	/**
	 * Computes the number of keys that are processed by the PACT.
	 * 
	 * @return the number of keys processed by the PACT.
	 */
	private long computeNumberOfProcessedKeys() {
		long numKey1 = 0;
		for(PactConnection c : this.input1) {
			long keys = c.getSourcePact().estimatedKeyCardinality;
		
			if(keys == -1) {
				numKey1 = -1;
				break;
			}
		
			numKey1 += keys;
		}	

		long numKey2 = 0;
		for(PactConnection c : this.input2) {
			long keys = c.getSourcePact().estimatedKeyCardinality;
		
			if(keys == -1) {
				numKey2 = -1;
				break;
			}
		
			numKey2 += keys;
		}	

		// Use output contract to estimate the number of processed keys
		switch(this.getOutputContract()) {
			case SameKeyFirst:
				return numKey1;
			case SameKeySecond:
				return numKey2;
		}

		if(numKey1 == -1 || numKey2 == -1)
			return -1;
		
		return numKey1 * numKey2;
	}
	
	/**
	 * Computes the number of stub calls for one processed key. 
	 * 
	 * @return the number of stub calls for one processed key.
	 */
	private double computeStubCallsPerProcessedKey() {
		long numRecords1 = 0;
		for(PactConnection c : this.input1) {
			long recs = c.getSourcePact().estimatedNumRecords;
		
			if(recs == -1) {
				return -1;
			}
		
			numRecords1 += recs;
		}	

		long numRecords2 = 0;
		for(PactConnection c : this.input2) {
			long recs = c.getSourcePact().estimatedNumRecords;
		
			if(recs == -1) {
				return -1;
			}
		
			numRecords2 += recs;
		}	

		long carthesianCard = numRecords1 * numRecords2;

		long numKey1 = 0;
		for(PactConnection c : this.input1) {
			long keys = c.getSourcePact().estimatedKeyCardinality;
		
			if(keys == -1) {
				numKey1 = -1;
				break;
			}
		
			numKey1 += keys;
		}

		long numKey2 = 0;
		for(PactConnection c : this.input2) {
			long keys = c.getSourcePact().estimatedKeyCardinality;
		
			if(keys == -1) {
				numKey2 = -1;
				break;
			}
		
			numKey2 += keys;
		}	

		
		switch(this.getOutputContract()) {
		case SameKeyFirst:
			if(numKey1 == -1)
				return -1;
			
			return carthesianCard / numKey1;
		case SameKeySecond:
			if(numKey2 == -1)
				return -1;
			
			return carthesianCard / numKey2;
		}

		if(numKey1 == -1 || numKey2 == -1)
			return -1;
			
		return carthesianCard / numKey1 / numKey2;
	}
	
	/**
	 * Computes the number of stub calls.
	 * 
	 * @return the number of stub calls.
	 */
	private long computeNumberOfStubCalls() {
		long numRecords1 = 0;
		for(PactConnection c : this.input1) {
			long recs = c.getSourcePact().estimatedNumRecords;
		
			if(recs == -1) {
				return -1;
			}
		
			numRecords1 += recs;
		}	

		long numRecords2 = 0;
		for(PactConnection c : this.input2) {
			long recs = c.getSourcePact().estimatedNumRecords;
		
			if(recs == -1) {
				return -1;
			}
		
			numRecords2 += recs;
		}	

		return numRecords1 * numRecords2;
	}
	
	/**
	 * Computes the width of output records
	 * 
	 * @return width of output records
	 */
	private double computeAverageRecordWidth() {
		CompilerHints hints = getPactContract().getCompilerHints();

		if(hints.getAvgBytesPerRecord() != -1) {
			// use hint if available
			return hints.getAvgBytesPerRecord();
		}

		
		
		long outputSize = 0;
		long numRecords = 0;
		for(PactConnection c : this.input1) {
			OptimizerNode pred = c.getSourcePact();
			
			if(pred != null) {
				// if one input (all of them are unioned) does not know
				// its output size or number of records, we a pessimistic and return "unknown" as well
				if(pred.estimatedOutputSize == -1 || pred.estimatedNumRecords == -1) {
					outputSize = -1;
					break;
				}
				
				outputSize += pred.estimatedOutputSize;
				numRecords += pred.estimatedNumRecords;
			}
		}

		double avgWidth = -1;

		if(outputSize != -1) {
			avgWidth = outputSize / (double)numRecords;
			if(avgWidth < 1)
				avgWidth = 1;
		}
		

		for(PactConnection c : this.input2) {
			OptimizerNode pred = c.getSourcePact();
			
			if(pred != null) {
				// if one input (all of them are unioned) does not know
				// its output size or number of records, we a pessimistic and return "unknown" as well
				if(pred.estimatedOutputSize == -1) {
					return avgWidth;
				}
				
				outputSize += pred.estimatedOutputSize;
				numRecords += pred.estimatedNumRecords;
			}
		}
		
		if(outputSize != -1) {
			avgWidth += outputSize / (double)numRecords;
			if(avgWidth < 2)
				avgWidth = 2;
		}

		return avgWidth;
	}
	
	/*
	 * (non-Javadoc)
	 * @see eu.stratosphere.pact.compiler.plan.OptimizerNode#computeOutputEstimates(eu.stratosphere.pact.compiler.DataStatistics)
	 */
	@Override
	public void computeOutputEstimates(DataStatistics statistics) {
		// TODO: mjsax
//		OptimizerNode pred1 = input1 == null ? null : input1.getSourcePact();
//		OptimizerNode pred2 = input2 == null ? null : input2.getSourcePact();
		OptimizerNode pred1 = null;
		OptimizerNode pred2 = null;
		CompilerHints hints = getPactContract().getCompilerHints();

		// check if preceding node is available
		if (pred1 == null || pred2 == null) {
			// Preceding node is not available, we take hints as given
			this.estimatedKeyCardinality = hints.getKeyCardinality();
			
			if(hints.getKeyCardinality() != -1 && hints.getAvgNumValuesPerKey() != -1) {
				this.estimatedNumRecords = (hints.getKeyCardinality() * hints.getAvgNumValuesPerKey()) >= 1 ? 
						(long) (hints.getKeyCardinality() * hints.getAvgNumValuesPerKey()) : 1;
			}
			
			if(this.estimatedNumRecords != -1 && hints.getAvgBytesPerRecord() != -1) {
				this.estimatedOutputSize = (this.estimatedNumRecords * hints.getAvgBytesPerRecord() >= 1) ? 
						(long) (this.estimatedNumRecords * hints.getAvgBytesPerRecord()) : 1;
			}
			
		} else {
			// We have a preceding node
			
			// ############# set default estimates
			
			// default output cardinality is equal to number of stub calls
			this.estimatedNumRecords = this.computeNumberOfStubCalls();
			// default key cardinality is -1
			this.estimatedKeyCardinality = -1;
			// default output size is equal to output size of previous node
			this.estimatedOutputSize = -1;
						
			
			// ############# output cardinality estimation ##############
			
			boolean outputCardEstimated = true;
				
			if(hints.getKeyCardinality() != -1 && hints.getAvgNumValuesPerKey() != -1) {
				// we have precise hints
				this.estimatedNumRecords = (hints.getKeyCardinality() * hints.getAvgNumValuesPerKey() >= 1) ?
						(long) (hints.getKeyCardinality() * hints.getAvgNumValuesPerKey()) : 1;
			} else if(hints.getAvgRecordsEmittedPerStubCall() != 1.0) {
				// we know how many records are in average emitted per stub call
				this.estimatedNumRecords = (this.computeNumberOfStubCalls() * hints.getAvgRecordsEmittedPerStubCall() >= 1) ?
						(long) (this.computeNumberOfStubCalls() * hints.getAvgRecordsEmittedPerStubCall()) : 1;
			} else {
				outputCardEstimated = false;
			}
						
			// ############# output key cardinality estimation ##########

			if(hints.getKeyCardinality() != -1) {
				// number of keys is explicitly given by user hint
				this.estimatedKeyCardinality = hints.getKeyCardinality();
				
			} else if(!this.getOutputContract().equals(OutputContract.None)) {
				// we have an output contract which might help to estimate the number of output keys
				
				if(this.getOutputContract().equals(OutputContract.UniqueKey)) {
					// each output key is unique. Every record has a unique key.
					this.estimatedKeyCardinality = this.estimatedNumRecords;
					
				} else if(this.getOutputContract().equals(OutputContract.SameKey) || 
						this.getOutputContract().equals(OutputContract.SameKeyFirst) || 
						this.getOutputContract().equals(OutputContract.SameKeySecond)) {
					// we have a samekey output contract
					
					if(hints.getAvgRecordsEmittedPerStubCall() < 1.0) {
						// in average less than one record is emitted per stub call
						
						// compute the probability that at least one stub call emits a record for a given key 
						double probToKeepKey = 1.0 - Math.pow((1.0 - hints.getAvgRecordsEmittedPerStubCall()), this.computeStubCallsPerProcessedKey());

						this.estimatedKeyCardinality = (this.computeNumberOfProcessedKeys() * probToKeepKey >= 1) ?
								(long) (this.computeNumberOfProcessedKeys() * probToKeepKey) : 1;
					} else {
						// in average more than one record is emitted per stub call. We assume all keys are kept.
						this.estimatedKeyCardinality = this.computeNumberOfProcessedKeys();
					}
				}
			} else if(hints.getAvgNumValuesPerKey() != -1 && this.estimatedNumRecords != -1) {
				// we have a hint for the average number of records per key
				this.estimatedKeyCardinality = (this.estimatedNumRecords / hints.getAvgNumValuesPerKey() >= 1) ? 
						(long) (this.estimatedNumRecords / hints.getAvgNumValuesPerKey()) : 1;
			}
			 
			// try to reversely estimate output cardinality from key cardinality
			if(this.estimatedKeyCardinality != -1 && !outputCardEstimated) {
				// we could derive an estimate for key cardinality but could not derive an estimate for the output cardinality
				if(hints.getAvgNumValuesPerKey() != -1) {
					// we have a hint for average values per key
					this.estimatedNumRecords = (this.estimatedKeyCardinality * hints.getAvgNumValuesPerKey() >= 1) ?
							(long) (this.estimatedKeyCardinality * hints.getAvgNumValuesPerKey()) : 1;
				}
			}
			
				
			// ############# output size estimation #####################

			double estAvgRecordWidth = this.computeAverageRecordWidth();
			
			if(this.estimatedNumRecords != -1 && estAvgRecordWidth != -1) {
				// we have a cardinality estimate and width estimate

				this.estimatedOutputSize = (this.estimatedNumRecords * estAvgRecordWidth) >= 1 ? 
						(long)(this.estimatedNumRecords * estAvgRecordWidth) : 1;
			}
			
			// check that the key-card is maximally as large as the number of rows
			if (this.estimatedKeyCardinality > this.estimatedNumRecords) {
				this.estimatedKeyCardinality = this.estimatedNumRecords;
			}
		}
	}

}