
import ij.*;
import ij.plugin.*;
import ij.plugin.frame.RoiManager;
import ij.gui.*;
import ij.process.*;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.lang.reflect.Array;



public class Multiplex_Pixel_Colocalization_ implements PlugIn 
{
  static String title = "Multiplex Pixel Colocalization";
  static final int validChannelMinCount = 1;
  static final int validChannelMaxCount = 8;
  static final String POSITIVE = "(+)";
  static final String NEGATIVE = "(-)";

  int stampCount = 0; /* Number of Image Stacks to import and analyze*/
  String[] imageTitles = null;
  ImagePlus[] inputImages = null; 
  double[] channelThresholds = null;
  int comboOptions = 0;
  int[][] colocalizationCounts = null; //count by picture/stack
  int sliceCount = 0;
  String outputfilepath = "C:\\Users\\Isaac\\Desktop\\";
  String outputfilename = "colocalizationCounts.csv";

  public void run(String arg) 
  {
    /* Check start conditions */
    if (IJ.versionLessThan("1.27w")) 
    {
      return;
    }

    /* How many image stacks the user upload*/
    stampCount = returnStampCount();
    if (stampCount == 0)
    {
      return;
    }

    /* Set up arrays based on channels selected by user*/
    inputImages = new ImagePlus[stampCount];
    channelThresholds = new double[stampCount];

    /* Get Input Images, Check Sizes, Apply ROI Masks*/
    if (!getStampsPostRoiMask()) 
    {
      IJ.showMessage(title, "Error Getting Images");
      return; 
    }
    
    /* Hold 2^(#slices) for each image stamp*/
    if (sliceCount == 0)
    {
      IJ.showMessage(title, "Slice Count is Zero. Unknown Error. Exiting.");
      return;
    }
    comboOptions = (int)Math.pow(2, sliceCount);
    colocalizationCounts = new int[stampCount][comboOptions]; 
    
    calculateColocolization();
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
   * Return: # of Image Stamps to analyze, 0 on ERR or Exit
   * */
  private int returnStampCount()
  {
    double count = 0; 
    PrintWriter pw = null;

    while(pw == null) 
    {
      GenericDialog gd = new GenericDialog(title);
      gd.addMessage("This is the number of image stacks you would like to analyze together.");
      gd.addNumericField("Image Stamp Count (whole number greater than zero):", 2, 0);
      gd.addStringField("Output Filepath", outputfilepath);
      gd.addStringField("Output Filename", outputfilename);
      gd.setCancelLabel("Exit");
      gd.showDialog();
      if (gd.wasCanceled()) 
      {
        return (int)0;
      }
      count = gd.getNextNumber();
      outputfilepath = gd.getNextString();
      outputfilename = gd.getNextString();
 
      if ((Double.isNaN(count)) || 
          (count < 0) ||
          (count != Math.ceil(count)))
      {
        IJ.showMessage(title, "ERR: Please enter a valid integer above zero:" + Integer.toString(validChannelMinCount) 
        + " and " + Integer.toString(validChannelMaxCount));
        return (int)0;
      }

      try 
      {
        pw = new PrintWriter(new File(outputfilepath + outputfilename));
      } 
      catch (FileNotFoundException e) 
      {
        IJ.showMessage(title, "ERR: Filepath does not exist. Please try again.");
        pw = null;
      }
    }
    pw.close();
    return (int)count;
  }

  /*
   * This will get the list of relevant channel names from the user
   * and validate they are the same size, before returning true or false
   * based on success or failure
   * Count: number of input images the user selected.
   * */
  
  private boolean getStampsPostRoiMask()
  {
    if (Array.getLength(inputImages) != stampCount)
    {
      IJ.showMessage(title, "ERR: Setup Failure. Image array length not equal to stampCount requested");
    }
    
    for (int i=0; i < stampCount; i++)
    {
      if(!getNextStamp(i))
      {
        IJ.showMessage(title, "ERR: Unknown Error Getting Input Image");
        return false;
      }
      if(!checkImageData(i))
      {
        IJ.showMessage(title, "ERR with Image Data, Exiting");
        return false;
      }
      if(!getApplyROI(i))
      {
        IJ.showMessage(title, "ERR failed to apply ROI, exiting.");
        return false;
      }
    }
    return true;
  }

  private boolean getNextStamp(int stampnumber)
  {
    /* Get the number of channels to analyze, and error check the input */
    int [] openWindowList = WindowManager.getIDList();
    if (openWindowList == null)
    {
      IJ.showMessage(title, "ERR: Please open images you would like to analyze.");
    }
    openWindowList = WindowManager.getIDList();
    if (openWindowList == null)
    {
      IJ.showMessage(title, "ERR: Unable to generate image options. Exiting.");
      return false; 
    }
    String [] imagetitleoptions = getImageOptions(openWindowList);
    GenericDialog gd = new GenericDialog(title);
    String option = "Channel: ";
    gd.addChoice(option, imagetitleoptions, imagetitleoptions[0]);
    gd.addNumericField("Channel Threshold Value (0-255):", 50, 1);
    gd.showDialog();
    if (gd.wasCanceled()) 
    {
      return false;
    }
    int imageIndex = gd.getNextChoiceIndex();
    channelThresholds[stampnumber] = gd.getNextNumber();
    inputImages[stampnumber] = WindowManager.getImage(openWindowList[imageIndex]);
    if (inputImages[stampnumber] == null)
    {
      IJ.showMessage(title, "ERR: Error loading Image.");
      return false; 
    }
   return true;
  }

  

  private boolean checkImageData(int i)
  {
	int w = 0, h = 0;
    /*Check images for consistent sizing*/
    if (i == 0)
    {
      sliceCount = inputImages[i].getStackSize();
      h = inputImages[i].getHeight();
      w = inputImages[i].getWidth();
      if((sliceCount == 0) || (h == 0) || (w == 0))
      {
        String imagesize = "slices::" + Integer.toString(sliceCount) +
                           " height:" + Integer.toString(h) +
                           " width:"+ Integer.toString(w);
        IJ.showMessage(title, "ERR: Image must exist. " + imagesize);
      }
      if (inputImages[i].getType()!= ImagePlus.GRAY8)
      {
        IJ.showMessage(title, "Must be Gray8 type");
        return false;
      }
    } 
    else 
    {
      int newSliceCount = inputImages[i].getStackSize();
      int newHeight = inputImages[i].getHeight();
      int newWidth = inputImages[i].getWidth();
      if((sliceCount != newSliceCount) || (newHeight == 0) || (newWidth == 0))
      {
        String imagesize = "stack:" + Integer.toString(sliceCount) +
                           " height:" + Integer.toString(h) +
                           " width:"+ Integer.toString(w) +
                           " new stack:" + Integer.toString(newSliceCount) +
                           " new height:" + Integer.toString(newHeight) +
                           " new width:"+ Integer.toString(newWidth) +
                           " Image Number: " + Integer.toString(i);
        IJ.showMessage(title, "ERR: Image sizes must be the same. " + imagesize);
        return false;
      }
      if (inputImages[i].getType()!= ImagePlus.GRAY8)
      {
        IJ.showMessage(title, "Must be Gray8 type");
        return false;
      }
    }
    return true;
  }

  private boolean getApplyROI(int stampNumber)
  {
    RoiManager roiMng = RoiManager.getRoiManager();
    WaitForUserDialog wd_roid = new WaitForUserDialog("USER ROI SELECT", "Select ROI using ROI Manger. \nNote if you load an ROI set and select no-ROIs all ROIs will be auto-selected.");
    wd_roid.show();
    Roi[ ] rois = roiMng.getSelectedRoisAsArray();
    if (rois.length == 0) 
    {
      IJ.showMessage(title,"NO ROI IN USE. Continue");
      return true;
    } 
    else
    {
       IJ.showMessage(title, "Roi Count = " + Integer.toString(rois.length));
    }
    int nwidth = inputImages[stampNumber].getWidth();
    int nheight = inputImages[stampNumber].getHeight();
    ImageProcessor imageProcessor = inputImages[stampNumber].getProcessor();

    for (int n=1; n<=sliceCount; n++) 
    {
      inputImages[stampNumber].setSlice(n);
      for (int x=0; x<nwidth; x++) 
      {
        for (int y=0; y<nheight; y++) 
        {
          if (roisContainPixel(rois,x,y) == false)
          {
          /*Note slice numbers start at 1*/
            imageProcessor.putPixelValue(x, y, 0);
          }
        }
      }   
    }
    return true;
  }

  private boolean roisContainPixel(Roi [] rois, int x, int y)
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
    ImageProcessor [] imageProcessor = new ImageProcessor[stampCount];

    for (int i=0; i<stampCount; i++)
    {
      imageProcessor[i] = inputImages[i].getProcessor();
    }
    

    for(int i=0; i<stampCount; i++)
    {
      int stampWidth = inputImages[i].getWidth();
      int stampHeight = inputImages[i].getHeight();
      int [][]colocalizationIndex = new int[stampWidth][stampHeight]; //Allows selection of right count to increase.
      for(int n=0; n<sliceCount; n++)
      {
        inputImages[i].setSlice(n+1);
        for (int x=0; x<stampWidth; x++) 
        {
          for (int y=0; y<stampHeight; y++) 
          {
            //Pixel Analysis 
            int pixelLit = (imageProcessor[i].getPixel(x,y) > channelThresholds[i]) ? 1:0;
            colocalizationIndex[x][y] = (colocalizationIndex[x][y] << 1) + pixelLit; 
          }
        }
      }
      for (int x=0; x<stampWidth; x++) 
      {
        for (int y=0; y<stampHeight; y++) 
        {
          if((colocalizationIndex[x][y]) > comboOptions)
          {
            IJ.showMessage(title, "OUt of bounds: combo " + Integer.toString(comboOptions) + "  col " + Integer.toString(colocalizationIndex[x][y]));
          } else {
          (colocalizationCounts[i][colocalizationIndex[x][y]])++;
          }
        }
      }
    }
  }



  private Boolean saveColocalizationCounts()
  {
    String toPrint = "";
    int[] bitmasks = new int[sliceCount];
    
    /*Create Bitmasks for each slice coloc combo*/
    for(int i=0; i<sliceCount; i++)
    {
      int shift = 1; 
      //toPrint = toPrint + channelTitles[i] + ",";
      toPrint = toPrint + Integer.toString(i) + ",";
      bitmasks[i] = shift << (sliceCount - 1 - i);
    }
    for (int n=0; n < stampCount; n++)
    {
      toPrint = toPrint + inputImages[n].getTitle() + ',';
    }
    toPrint = toPrint + "\n";
    
    for(int i=0; i < comboOptions; i++)
    {
      for(int j=0; j<sliceCount; j++)
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
      int sumTotal = 0;
      for (int n=0; n < stampCount; n++)
      {
        sumTotal += colocalizationCounts[n][i];
        toPrint = toPrint + Integer.toString(colocalizationCounts[n][i]) + ",";
      }
      toPrint = toPrint + Integer.toString(sumTotal) + "\n";
    }
    PrintWriter pw = null;
    try 
    {
        pw = new PrintWriter(new File(outputfilepath + outputfilename));
    } 
    catch (FileNotFoundException e) 
    {
        e.printStackTrace();
        IJ.showMessage(title, e.toString());
        return false;
    }
    pw.write(toPrint);
    pw.close();
    return true;
  }

}
