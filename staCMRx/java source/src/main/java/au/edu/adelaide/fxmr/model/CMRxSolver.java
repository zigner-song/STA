package au.edu.adelaide.fxmr.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.TreeSet;

import au.edu.adelaide.fxmr.joptimizer.functions.SimpleLinearConstraint;
import au.edu.adelaide.fxmr.model.mr.MRSolver;
import au.edu.adelaide.fxmr.model.mr.MRSolverAJOptimiser;
import au.edu.adelaide.fxmr.model.ui.StatusFrame;
import au.edu.adelaide.fxmr.om.OMUtil;
import cern.colt.matrix.DoubleMatrix2D;
import cern.colt.matrix.impl.DenseDoubleMatrix2D;
import gnu.trove.map.hash.TIntObjectHashMap;
import gnu.trove.set.hash.TIntHashSet;

public class CMRxSolver {
	private static final double ZERO_TOL = 1e-5;
	/**
	 * If a call to MR fails, abort!
	 */
	private boolean easyFail;
	private boolean allowCyclic = true;
	private double tolerance = 0;
	private double mrTolerance1 = 0;
	private double mrTolerance2 = 0;

	public CMRxSolver() {
	}

	public CMRSolution solve(CMRxProblem problem) {
		return this.solve(problem, false);
	}

	public CMRSolution solve(CMRxProblem problem, boolean showWindow) {
		SolverListener sl = null;
		if (showWindow)
			sl = new StatusFrame();
		return solve(problem, sl);
	}

	public CMRSolution solve(CMRxProblem problem, SolverListener sl) {
		return solve(problem, sl, false, -1);
	}

	public CMRSolution solve(CMRxProblem problem, SolverListener sl, boolean targetSet, double target) {
		long start = System.nanoTime();
		double finalTarget = targetSet ? target - problem.getfAddWeightedMeans() : 0;
		ArrayList<CMRIter> iter = new ArrayList<>();
		int nvar = problem.getNVar();
		double[][] means = problem.getMeans();
		DoubleMatrix2D[] weights = problem.getWeights();
		double fFloor = Double.NEGATIVE_INFINITY;
		double fBar = Double.POSITIVE_INFINITY;
		double[][] xBar = null;
		int[][] cv = problem.getCv();
		int[] tmpCVSet = new int[nvar];
		int ncond = problem.getNCond();
		DoubleMatrix2D tmpVolumes = new DenseDoubleMatrix2D(ncond, ncond);
		int[][] tmpZoneNumbers = new int[ncond][ncond];
		int newTrialID = 0;
		TIntObjectHashMap<int[]> zDecodeCache = new TIntObjectHashMap<>();
		int fBarReductions = 0;
		VisitedSet visited = new VisitedSet();

		TIntHashSet infeasZones = problem.getInfeasZones();
		int[] infeas = isFeasible3n(problem.getMeans(), infeasZones, tmpVolumes, tmpZoneNumbers);
		HashSet<SimpleLinearConstraint>[] adjBar = null;
		int collisions = 0;
		int cyclicAvoided = 0;

		if (infeas == null)
			return problem.createSolution(0, problem.getMeans(), iter,
					(double) (System.nanoTime() - start) / 1_000_000_000, null, 0, 0);

		TreeSet<CMRxTrial> remaining = new TreeSet<>();

		MRSolverAJOptimiser mrSolver = new MRSolverAJOptimiser();
		if (mrTolerance1 != 0)
			mrSolver.setTolerance(mrTolerance1, mrTolerance2);

		// mrSolver.setFailQuietly(easyFail);
		mrSolver.setAllowCyclicProblems(allowCyclic);

		// Add first CMRxTrial
		remaining.add(new CMRxTrial(mrSolver, problem.getAdj(), nvar, weights, means));

		if (sl != null)
			sl.updateStatus("Running CMRx");
		// Get a reasonable upper bound
		CMRxTrial bestGreedy = getFeasible6(problem, mrSolver, zDecodeCache);
		if (bestGreedy != null) {
			xBar = bestGreedy.getxPrime();
			fBar = bestGreedy.getF();
			adjBar = bestGreedy.getAdjs();
			// For GC
			bestGreedy = null;
			fBarReductions++;

			if (targetSet && fBar < finalTarget) {
				// upper bound is less than target!
				// System.out.println(fFloor + ", " + fBar + " <= " + target);
				return new CMRSolution(fBar + problem.getfAddWeightedMeans(), null, null,
						(double) (System.nanoTime() - start) / 1_000_000_000, null,
						mrSolver.getCalls(), fBarReductions);
			}
		}

		double tolerancem1 = 1.0 - tolerance;

		while (!remaining.isEmpty() && fFloor < fBar * tolerancem1) {
			CMRxTrial current = remaining.pollFirst();
			fFloor = current.getF();

			double upperFloor = remaining.isEmpty() ? fFloor : remaining.last().getF();
			if (sl != null && iter.size() % 100 == 0) {
				if (!sl.updateStatus(fFloor, fBar, upperFloor, remaining.size(), new int[] { iter.size() }, 0, fBarReductions,
						cyclicAvoided))
					break;
			}

			if (targetSet && (fBar < finalTarget || (fFloor >= finalTarget && fBar >= finalTarget))) {
				// upper bound < target or lower bound > target
				return new CMRSolution(fBar + problem.getfAddWeightedMeans(), null, null,
						(double) (System.nanoTime() - start) / 1_000_000_000, null,
						mrSolver.getCalls(), fBarReductions);
			}

			iter.add(new CMRIter(fFloor, fBar, upperFloor, remaining.size()));

			if (fFloor < fBar * tolerancem1) {
				current.run();
				// current.run();
				double[][] xPrimes = current.getxPrime();
				if (xPrimes == null && easyFail) {
					return null;
				} else if (xPrimes == null) {
					if (allowCyclic) {
						// This was caused by a failed MR call (the warning
						// would have been printed elsewhere)
						// System.err.println("Warning: ..." );
					} else {
						cyclicAvoided++;
					}
				} else {
					double fit = current.getF();
					infeas = isFeasible3n(xPrimes, infeasZones, tmpVolumes, tmpZoneNumbers);

					if (infeas == null) {
						// Solution is feasible
						if (fit < fBar) {
							// Solution is better than current best
							fBar = fit;
							fBarReductions++;
							xBar = xPrimes;
							adjBar = current.getAdjs();
							Iterator<CMRxTrial> iterR = remaining.descendingIterator();
							while (iterR.hasNext()) {
								if (iterR.next().getF() > fit)
									iterR.remove();
								else
									break;
							}
						}
					} else if (fit < fBar) {
						// Solution not feasible - make it so!
						int zone = infeas[2];
						int[] signVector = zDecodeCache.get(zone);
						if (signVector == null) {
							signVector = OMUtil.zDecode(zone, nvar);
							zDecodeCache.put(zone, signVector);
						}

						int negIndex = infeas[0];
						int posIndex = infeas[1];

						for (int[] covector : cv) {
							int nS = 0;
							for (int i = 0; i < nvar; i++)
								if (covector[i] != signVector[i] && signVector[i] != 0)
									tmpCVSet[nS++] = i;

							if (nS > 0) {
								CMRxTrial newTrial = current.split(newTrialID++);

								for (int i = 0; i < nS; i++) {
									int k = tmpCVSet[i];
									if (covector[k] > 0)
										newTrial.addConstraint(k, posIndex, negIndex);
									if (covector[k] < 0)
										newTrial.addConstraint(k, negIndex, posIndex);
								}

								if (visited.contains(newTrial)) {
									collisions++;
								} else {
									remaining.add(newTrial);
									visited.add(newTrial);
								}
							} else {
							}
						}
					}
				}
			}
		}

		if (sl != null)
			sl.setFinished();

		// One last iter for plotting
		iter.add(new CMRIter(fBar, fBar, fBar, remaining.size()));

		return problem.createSolution(fBar, xBar, iter, (double) (System.nanoTime() - start) / 1_000_000_000, adjBar,
				mrSolver.getCalls(), fBarReductions);
	}

	/**
	 * modified version of isFeasible3n - given arbitrary ordered y vectors,
	 * will determine the largest non coupled monotonic pair.
	 * 
	 * @param y
	 *            2d array of means from StatsSTA
	 * @param infeasZones
	 *            zone numbers corresponding to infeasible points. For example,
	 *            [+1 -1] corresponds to zone number 2
	 * 
	 * @return null if feasible or the largest inversion if not (ZERO INDEXED!).
	 *         Zone number is also tacked on the end (Java implementation issue)
	 */
	public static int[] isFeasible3n(double[][] y, TIntHashSet infeasZones, DoubleMatrix2D volumes, int[][] zoneNumbers) {
		int nvar = y.length;
		int ny = y[0].length;
		for (int i = 0; i < ny; i++)
			Arrays.fill(zoneNumbers[i], 0);

		volumes.assign(1);

		for (int i = 0; i < nvar; i++) {
			double[] curY = y[i];
			long curPow = CMRUtil.POW_3[nvar - i - 1];

			for (int row = ny; --row >= 0;)
				for (int column = ny; --column >= row;) {
					double diff = curY[row] - curY[column];
					if (Math.abs(diff) > ZERO_TOL) {
						volumes.setQuick(row, column, volumes.getQuick(row, column) * diff);
						zoneNumbers[row][column] += (int) (Math.signum(diff)) * curPow;
					}
				}
		}

		double maxVol = 0;
		int maxRow = -1;
		int maxColumn = -1;

		for (int row = ny; --row >= 0;) {
			for (int column = ny; --column >= row;) {
				int curZone = Math.abs(zoneNumbers[row][column]);
				if (infeasZones.contains(curZone)) {
					double curVol = Math.abs(volumes.getQuick(row, column));
					if (curVol > maxVol) {
						maxVol = curVol;
						maxRow = row;
						maxColumn = column;
					}
				}
			}
		}

		if (maxRow != -1)
			return new int[] { maxRow, maxColumn, zoneNumbers[maxRow][maxColumn] };
		return null;
	}

	/**
	 * Do a depth first search of a greedy feasible solution in order to obtain
	 * a reasonable estimate of the upper bound.
	 * 
	 * @return
	 */
	public static CMRxTrial getFeasible5(CMRxProblem problem, MRSolver solver) {
		int nvar = problem.getNVar();
		int nvec = problem.getCv().length;
		TIntHashSet infeasZones = problem.getInfeasZones();
		int[][] cv = problem.getCv();
		int ncond = problem.getNCond();
		DoubleMatrix2D tmpVolumes = new DenseDoubleMatrix2D(ncond, ncond);
		int[][] tmpZoneNumbers = new int[ncond][ncond];

		double[][] xPrime = problem.getMeans();

		CMRxTrial[] curBests = new CMRxTrial[nvec];
		for (int i = 0; i < nvec; i++)
			curBests[i] = new CMRxTrial(solver, problem.getAdj(), nvar, problem.getWeights(), problem.getMeans());

		int[] infeas = isFeasible3n(xPrime, infeasZones, tmpVolumes, tmpZoneNumbers);
		while (infeas != null) {
			int negIndex = infeas[0];
			int posIndex = infeas[1];

			CMRxTrial bestTrial = null;

			for (int ivec = 0; ivec < nvec; ivec++) {
				for (int ivar = 0; ivar < nvar; ivar++) {
					if (cv[ivec][ivar] >= 0) {
						curBests[ivec].addConstraint(ivar, posIndex, negIndex);
					} else if (cv[ivec][ivar] <= 0) {
						curBests[ivec].addConstraint(ivar, negIndex, posIndex);
					}
				}
				curBests[ivec].run();
				if (bestTrial == null || curBests[ivec].getF() < bestTrial.getF())
					bestTrial = curBests[ivec];
			}

			if (bestTrial.getxPrime() == null)
				// Hit circular constraints from every covector
				return null;

			infeas = isFeasible3n(bestTrial.getxPrime(), infeasZones, tmpVolumes, tmpZoneNumbers);
			if (infeas == null) {
				return bestTrial;
			} else {
				// Copy adjacency matries to all curBests
				for (int i = 0; i < nvec; i++)
					if (curBests[i] != bestTrial)
						curBests[i].setConstraintsFrom(bestTrial);
			}
		}
		// Something went wrong?!
		return null;
	}

	/**
	 * Do a depth first search of a greedy feasible solution in order to obtain
	 * a reasonable estimate of the upper bound.
	 * 
	 * @param zDecodeCache
	 * 
	 * @return
	 */
	public static CMRxTrial getFeasible6(CMRxProblem problem, MRSolver solver, TIntObjectHashMap<int[]> zDecodeCache) {
		int nvar = problem.getNVar();
		TIntHashSet infeasZones = problem.getInfeasZones();
		int[][] cv = problem.getCv();
		int ncond = problem.getNCond();
		DoubleMatrix2D tmpVolumes = new DenseDoubleMatrix2D(ncond, ncond);
		int[][] tmpZoneNumbers = new int[ncond][ncond];
		int[] tmpCVSet = new int[nvar];
		int trialIndex = 0;

		double[][] xPrime = problem.getMeans();

		CMRxTrial curBest = new CMRxTrial(solver, problem.getAdj(), nvar, problem.getWeights(), problem.getMeans());

		int[] infeas = isFeasible3n(xPrime, infeasZones, tmpVolumes, tmpZoneNumbers);
		while (infeas != null) {
			int negIndex = infeas[0];
			int posIndex = infeas[1];

			int zone = infeas[2];
			int[] signVector = zDecodeCache.get(zone);
			if (signVector == null) {
				signVector = OMUtil.zDecode(zone, nvar);
				zDecodeCache.put(zone, signVector);
			}

			CMRxTrial newBest = null;
			for (int[] covector : cv) {
				int nS = 0;
				for (int i = 0; i < nvar; i++)
					if (covector[i] != signVector[i] && signVector[i] != 0)
						tmpCVSet[nS++] = i;

				if (nS > 0) {
					CMRxTrial newTrial = curBest.split(trialIndex++);

					for (int i = 0; i < nS; i++) {
						int k = tmpCVSet[i];
						if (covector[k] > 0)
							newTrial.addConstraint(k, posIndex, negIndex);
						if (covector[k] < 0)
							newTrial.addConstraint(k, negIndex, posIndex);
					}

					newTrial.run();
					if (newTrial.getxPrime() != null && (newBest == null || newTrial.getF() < newBest.getF()))
						newBest = newTrial;
				}
			}

			if (newBest == null)
				return null;

			curBest = newBest;
			infeas = isFeasible3n(curBest.getxPrime(), infeasZones, tmpVolumes, tmpZoneNumbers);
		}
		return curBest;
	}

	public void setEasyFail(boolean easyFail) {
		this.easyFail = easyFail;
	}

	public double getTolerance() {
		return tolerance;
	}

	public void setTolerance(double tolerance) {
		this.tolerance = tolerance;
	}

	public boolean isAllowCyclic() {
		return allowCyclic;
	}

	public void setAllowCyclic(boolean allowCyclic) {
		this.allowCyclic = allowCyclic;
	}

	public double getMrTolerance1() {
		return mrTolerance1;
	}

	public void setMrTolerance1(double mrTolerance1) {
		this.mrTolerance1 = mrTolerance1;
	}

	public double getMrTolerance2() {
		return mrTolerance2;
	}

	public void setMrTolerance2(double mrTolerance2) {
		this.mrTolerance2 = mrTolerance2;
	}
}