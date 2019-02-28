
import ij.*;
import ij.plugin.*;
import ij.plugin.frame.RoiManager;
import ij.gui.*;
import ij.process.*;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.PrintWriter;



public class Multiplex_Pixel_Colocalization_ implements PlugIn 
{
  static String title = "Multiplex Pixel Colocalization";
  static final int validChannelMinCount = 2;
  static final int validChannelMaxCount = 8;
  static final String POSITIVE = "(+)";
  static final String NEGATIVE = "(-)";

  int channelCount = 0;
  double[] channelThreshold = null;
  String[] channelTitles = null;
  ImagePlus[] inputImages = null;
  ImagePlus outputImage;
  ImageStack outputImageStack; 
  int[] colocalizationCounts = null; //count by picture/stack
  int width = 0, height = 0, stackSize = 0;
  String outputfilename = "colocalizationCounts.csv";

  public void run(String arg) 
  {
    int[] openWindowList = null; //List of all open windows
    String[] imageTitleList = null;
    
    /* Check start conditions */
    if (IJ.versionLessThan("1.27w")) 
    {
      return;
    }

    /* Get the number of channels to analyze, and error check the input */
    openWindowList = WindowManager.getIDList();
    if (openWindowList == null)
    {
      IJ.showMessage(title, "ERR: Please open all channels and images you would like to analyze.");
      return;
    }
    imageTitleList = getImageOptions(openWindowList);
    channelCount = returnChannelCount(imageTitleList.length);
    if (channelCount == 0)
    {
      return;
    }
 
    /* Set up arrays based on channels selected by user*/
    inputImages = new ImagePlus[channelCount];
    channelThreshold = new double[channelCount];
    //There are 2^(#images) options for overlap
    colocalizationCounts = new int[(int)Math.pow(2, channelCount)]; 
    
    
    /*Get info on channels to analyze*/
    if (!getChannels(openWindowList)) 
    {
      return; 
    }
    outputImage = inputImages[0].duplicate();

    calculateColocolization();
    outputImage.show();
    if(!saveColocalizationCounts())
    {
      IJ.showMessage("ERR: Failed to save CSV file");
      return;
    }
    IJ.showMessage(title, "Analysis Successful! Congrats! And best of luck saving the world :)");
  }
  
  /*
   * This function is sent the openWindowList and creates 
   * a list of strings to return using only image names.
   * */
  private String[] getImageOptions(int[] openWindowList)
  {
    String[] titles = new String[openWindowList.length];
    for (int i=0; i<openWindowList.length; i++) 
    {
      ImagePlus imp = WindowManager.getImage(openWindowList[i]);
      if (imp!=null) 
      {
        titles[i] = imp.getTitle();
      } else {
        titles[i] = "";
      }
    }
    return titles;
  }


  /*
   * This function prompts the user to enter the channel count
   * that they would like to analyze. It validates the number is 
   * between the minimum and maximum # of channels this plugin 
   * can analyze and returns the value as an int.
   * It also verifies correct number of image windows are open. 
   * Return: # of channels to analyze, 0 on ERR or Exit
   * */
  private int returnChannelCount(int imagesOpen)
  {
    double count = 0; 
    GenericDialog gd = new GenericDialog(title);
    gd.addMessage("Please make sure all channels you want to analyze are open.");
    gd.addNumericField("Channel Count(2-6):", 2, 0);
    gd.setCancelLabel("Exit");
    gd.showDialog();
    if (gd.wasCanceled()) 
    {
      return (int)0;
    }
    count = gd.getNextNumber();
    if (Double.isNaN(count))
    {
      IJ.showMessage(title, "ERR: Please enter a valid integer between (including):" + Integer.toString(validChannelMinCount) 
      + " and " + Integer.toString(validChannelMaxCount));
      return (int)0;
    }
    if (count > imagesOpen)
    {
      IJ.showMessage(title, "Please make sure all channels you want to analyze are open");
      return (int)0;
    }
    if ((count < validChannelMinCount) || 
        (count > validChannelMaxCount) ||
        (count != Math.ceil(count)))
    {
      IJ.showMessage(title, "ERR: Channel Count must be an integer between " + Integer.toString(validChannelMinCount) 
        + " and " + Integer.toString(validChannelMaxCount) + "  Count Entered: " + Double.toString(count));
      return (int)0;
    }
    return (int)count;
  }

  /*
   * This will get the list of relevant channel names from the user
   * and validate they are the same size, before returning true or false
   * based on success or failure
   * */
  
  private boolean getChannels(int[] openWindowList) 
  {
    String[] imagetitleoptions = getImageOptions(openWindowList);
    GenericDialog gd = new GenericDialog(title);
    for(int i = 0; i < channelCount; i++) 
    {
      String option = "Channel #" + Integer.toString(i) + ": ";
      gd.addChoice(option, imagetitleoptions, imagetitleoptions[i]);
      gd.addNumericField("Channel Threshold Value (0-255):", 50, 1);
      gd.addMessage(" ");
    }
    gd.showDialog();
    if (gd.wasCanceled()) 
    {
      return false;
    }
    
    int[] imageIndex = new int[channelCount];
    for(int i = 0; i < channelCount; i++) 
    {
      imageIndex[i] = gd.getNextChoiceIndex();
      channelThreshold[i] = gd.getNextNumber();
      inputImages[i] = WindowManager.getImage(openWindowList[imageIndex[i]]);
  
      /*Check images for consistent sizing*/
      if (i == 0)
      {
        stackSize = inputImages[i].getStackSize();
        height = inputImages[i].getHeight();
        width = inputImages[i].getWidth();
        if (inputImages[i].getType()!= ImagePlus.GRAY8)
        {
          IJ.showMessage(title, "Must be Gray8 type");
          return false;
        }
      } 
      else 
      {
        int newstackSize = inputImages[i].getStackSize();
        int newheight = inputImages[i].getHeight();
        int newwidth = inputImages[i].getWidth();
        if((stackSize != newstackSize) || (height != newheight) || (width != newwidth))
        {
          String imagesize = "stack:" + Integer.toString(stackSize) +
                             " height:" + Integer.toString(height) +
                             " width:"+ Integer.toString(stackSize) +
                             " new stack:" + Integer.toString(newstackSize) +
                             " new height:" + Integer.toString(newheight) +
                             " new width:"+ Integer.toString(newstackSize) +
                             " Image Number: " + Integer.toString(i);
          IJ.showMessage(title, "ERR: Image sizes must be the same" + imagesize);
          return false;
        }
        if (inputImages[i].getType()!= ImagePlus.GRAY8)
        {
          IJ.showMessage(title, "Must be Gray8 type");
          return false;
        }
      }
    }
    return true;
  }

  
  private boolean doesthiscontain(Roi [] rois, int x, int y)
  {
     for (int i = 0; i < rois.length; i++)
     {
       if(rois[i].contains(x, y))
       {
         return true;
       }
     }
    return false;
  }
  /*
   * This will step through all stack/image options
   * And tally colocalization counts for each channel combo option
   * This info is stored in colocalizationCounts array.
   * if you treat the colocalizationIndex like binary, each bit
   * represents a channel. so we shift in each channel true/false 
   * to create the appropriate colocalization option 
   * */
  private void calculateColocolization()
  {
    int[] pixelLit = new int[channelCount + 1];
    ImageProcessor [] imageProcessor = new ImageProcessor[channelCount + 1];
    ImageStack[] stack = new ImageStack[channelCount + 1];
    float[][] ctable = new float[channelCount + 1][];
    RoiManager roiMng = RoiManager.getRoiManager();
    
    WaitForUserDialog wd_roid = new WaitForUserDialog("USER ROI SELECT", "Select Rois Please");
    wd_roid.show();
    Roi[ ] rois = roiMng.getSelectedRoisAsArray();
    if (rois.length == 0) 
    {
      IJ.showMessage(title,"NO ROI IN USE. Continue");
    } else
    {
     IJ.showMessage(title, "Using Rois = " + Integer.toString(rois.length));
    }

 
    //Grab information for each channel
    for(int i=0; i<channelCount; i++)
    {
      stack[i] = inputImages[i].getStack();
      ctable[i] = inputImages[i].getCalibration().getCTable();
    }
    

    stack[channelCount] = outputImage.getStack();
    ctable[channelCount] = outputImage.getCalibration().getCTable();


    for (int n=1; n<=stackSize; n++) 
    {
      for (int i=0; i<(channelCount+1); i++)
      {
        imageProcessor[i] = stack[i].getProcessor(n);
        imageProcessor[i].setCalibrationTable(ctable[i]);
      }
      
      for (int x=0; x<width; x++) 
      {
        for (int y=0; y<height; y++) 
        {
          if ((rois.length == 0) || ((rois.length != 0) && doesthiscontain(rois,x,y)))
          {
          //Pixel Analysis 
          int colocalizationIndex = 0x0; //Allows selection of right count to increase.
          pixelLit[channelCount] = 1;

          for(int i=0; i<channelCount; i++)
          {
            pixelLit[i] = (imageProcessor[i].getPixel(x,y) > channelThreshold[i])?1:0;
            pixelLit[channelCount] = pixelLit[i] & pixelLit[channelCount];
            colocalizationIndex = (colocalizationIndex << 1) + pixelLit[i]; 
          }          
          imageProcessor[channelCount].putPixelValue(x, y, (pixelLit[channelCount]==1)?255:0);
          colocalizationCounts[colocalizationIndex]++;
        }
        else 
        {
          imageProcessor[channelCount].putPixelValue(x, y, 0);
        }
      }   
      }
      IJ.showProgress((double)n/stackSize);
      IJ.showStatus(n+"/"+stackSize);
    }
  }
  
  
  private Boolean saveColocalizationCounts()
  {
    String toPrint = "";
    int[] bitmasks = new int[channelCount];
    
    /*Create Bitmasks for each channel*/
    for(int i=0; i<channelCount; i++)
    {
      int shift = 1; 
      //toPrint = toPrint + channelTitles[i] + ",";
      toPrint = toPrint + Integer.toString(i) + ",";
      bitmasks[i] = shift << (channelCount - 1 - i);
    }
    toPrint = toPrint + "pixel count\n";
    
    for(int i=0; i < colocalizationCounts.length; i++)
    {
      for(int j=0; j<channelCount; j++)
      {
        if((i & bitmasks[j]) != 0)
        {
          toPrint = toPrint + POSITIVE + ",";
        } 
        else 
        {
          toPrint = toPrint + NEGATIVE + ",";
        }
      }
      toPrint = toPrint + Integer.toString(colocalizationCounts[i]) + "\n";
    }
    IJ.showMessage(title, "Saving File");
    PrintWriter pw = null;
    try {
        pw = new PrintWriter(new File("C:/Users/Isaac/OneDrive - UW/Research- Kiem/Experimental Histopathology Requests/Slide Scanning-Analysis/Fluorescence/Multiplex IHC_GCs/AmFAR CD4CAR 6plex Images/NewData.csv"));
    } catch (FileNotFoundException e) {
        e.printStackTrace();
        IJ.showMessage(title, e.toString());
        return false;
    }
    pw.write(toPrint);
    pw.close();
    return true;
  }

}
