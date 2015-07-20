package admm;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import ilog.concert.IloException;
import ilog.concert.IloNumExpr;
import ilog.concert.IloNumVar;
import ilog.cplex.IloCplex;








import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hama.bsp.BSPPeer;
//import org.apache.commons.math3.linear.MatrixUtils;
//import org.apache.commons.math3.linear.RealMatrix;
import org.codehaus.jackson.annotate.JsonProperty;

public class SlaveContext {
	double[] x_salve;
	private double gamma = 1;
	private double alpha;
	//private static final double rho = 0.01;
	private double rho;
	private double[] xi_max;
	private double[] xi_min;
	double[] xMean;
	private double[] u;
	private SlaveData slaveData;
	private double[] x_optimal;
	private double[] x;
	int currentEVNo;
	private String evFileName;
	private boolean firstIteration = true;
	private Configuration conf;
	
	public SlaveContext(String fileName, double[] xMean, double[] u, int currentEVNo, double rhoValue, boolean isFirstIteration, BSPPeer<NullWritable, NullWritable,IntWritable, Text, Text> peer) throws IOException
	{	
		firstIteration = isFirstIteration;
		conf = peer.getConfiguration();
		
		slaveData = Utils.LoadSlaveDataFromMatFile(fileName, firstIteration, peer);
		//peer.write(new IntWritable(1), new Text("OUT"));
		this.x = slaveData.getXOptimal(); //Read the last optimal value directly from the .mat file
		//System.out.println("==============================");
		//Utils.PrintArray(this.x);
		//System.out.println("==============================");
		
		rho = rhoValue;
		evFileName = fileName; 
		
		this.alpha = (0.05/3600) * (15*60);
		//System.out.println(">>>>>>> APLHA: " + this.alpha);
		
		this.xi_max = Utils.scalerMultiply(this.slaveData.getD(), 4);
		this.xi_min = Utils.scalerMultiply(this.slaveData.getD(), -4);
		
		//Remove this
//		System.out.println("PRINTING XI_MAX and MIN");
		//Utils.PrintArray(xi_max);
		//Utils.PrintArray(xi_min);
//		System.out.println("PRINTING XI_MAX and MIN");
		//Remove this
		
		this.xMean = xMean;
		this.u = u;
		this.currentEVNo = currentEVNo;
	}
	
	public double optimize() throws IloException, FileNotFoundException
	{
		IloCplex cplex = new IloCplex();
		OutputStream out = new FileOutputStream("logfile_slave");
		cplex.setOut(out);
		
		//IloNumVar[] x_i = cplex.numVarArray(x.length, Double.MIN_VALUE, Double.MAX_VALUE);
		IloNumVar[] x_i = new IloNumVar[x.length];
		
		for(int i = 0; i < x.length ; i++) {
			x_i[i] = cplex.numVar(xi_min[i], xi_max[i]);
		}
		
	    double gammaAlpha = this.gamma * this.alpha;
		double[] data = subtractOldMeanU(x);
		
		IloNumExpr[] exps = new IloNumExpr[data.length];
		
		for(int i =0; i< data.length; i++)
		{	
			//exps[i] = cplex.sum(cplex.prod(gammaAlpha, cplex.square(x_i[i])) ,cplex.prod(rho/2, cplex.square(cplex.sum(x_i[i], cplex.constant(-data[i])))));
			exps[i] = cplex.sum(cplex.prod(gammaAlpha, cplex.square(x_i[i])) ,cplex.prod(rho/2, cplex.square(cplex.sum(x_i[i], cplex.constant(data[i])))));
		}
		
		IloNumExpr rightSide = cplex.sum(exps);
		cplex.addMinimize(rightSide);
		
		IloNumExpr[] AXExpEq = new IloNumExpr[data.length];
		
		for(int j = 0; j < data.length ; j++ )
		{
			//This constraint is already defined in the variable boundaries.
			//x_min <= x_i <= x_max
//			cplex.addLe(x_i[j], xi_max[j]);
//			cplex.addGe(x_i[j], xi_min[j]);
 
			
			//A_i*x_i = R
			//cplex.addEq(cplex.prod(x_i[j], this.slaveData.getA()[j]), this.slaveData.getR());
			AXExpEq[j] = cplex.prod(x_i[j], this.slaveData.getA()[j]);
		}
		cplex.addEq(cplex.sum(AXExpEq), this.slaveData.getR());
		
		//S_min <= B_i*x_i <= S_max
		for(int h=0; h < this.slaveData.getB().length; h++)
		{
			IloNumExpr[] BXExpLe = new IloNumExpr[this.slaveData.getB()[0].length];
			IloNumExpr[] BXExpGe = new IloNumExpr[this.slaveData.getB()[0].length];
			
			//System.out.print("[");
			for(int f=0; f < this.slaveData.getB()[0].length; f++)
			{
				//NOTE: REmove this start
//				if(f == this.slaveData.getB()[0].length - 1)
//				{
//					System.out.print(this.slaveData.getB()[h][f]);
//				}
//				else
//				System.out.print(this.slaveData.getB()[h][f] + ",");
				//Note: remove this end
				
				BXExpLe[f] = cplex.prod(x_i[f],this.slaveData.getB()[h][f]);
				BXExpGe[f] = cplex.prod(x_i[f],this.slaveData.getB()[h][f]);
			}
//			System.out.print("]");
//			System.out.println();
			
			cplex.addLe(cplex.sum(BXExpLe), this.slaveData.getSmax()[h]);
			cplex.addGe(cplex.sum(BXExpGe), this.slaveData.getSmin()[h]);
		}
		
		//if(firstIteration)
			//cplex.exportModel("EV_" + currentEVNo + ".lp");
		
		cplex.solve();
		
		System.out.println("Slave Output value: " + cplex.getObjValue() + "  CurrentEV: " + this.currentEVNo);
		System.out.println(cplex.getStatus());
		
		x_optimal = new double[x_i.length];
		
		for(int u=0; u< x_i.length; u++)
		{
			x_optimal[u] = cplex.getValues(x_i)[u];
		}
		
		System.out.println("PRINTING X_OPTIMAL VALUE");
		Utils.PrintArray(x_optimal);
		
		//Write the x_optimal to mat file
		Utils.SlaveXToMatFile(evFileName, x_optimal, conf);
		
		return cplex.getObjValue();
	}
	
	
	private double[] subtractOldMeanU(double[] xold)
	{
		xold = Utils.scalerMultiply(xold, -1);
		return Utils.vectorAdd(Utils.vectorAdd(xold, this.xMean), this.u);
	}
	
	public int getCurrentEVNo()
	{
		return this.currentEVNo;
	}
	
	public double[] getXOptimalSlave()
	{
		return this.x_optimal;
	}
	
	public double getAlpha()
	{
		return this.alpha;
	}
	
	public double[] getXimax()
	{
		return this.xi_max;
	}
	
	public double[] getXimin()
	{
		return this.xi_min;
	}
	
	public void setU(double[] u)
	{
		this.u = u;
	}
	
	public void setXMean(double[] xmean)
	{
		this.xMean = xmean;
	}
	
	public double[] getU()
	{
		return this.u;
	}
	
	public double[] getXMean()
	{
		return this.xMean;
	}
	
	public double[] getX()
	{
		return this.x;
	}
	
}
