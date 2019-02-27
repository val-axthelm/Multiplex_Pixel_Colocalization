
import ij.*;
import ij.plugin.*;
import ij.gui.*;
import ij.process.*;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.PrintWriter;


/*
 * This program takes up to 8 images to analyze. 
 * Each image is a different channel to compare. 
 * The assumption is that there is only one stack per image.
 * If there are more, it will only analyze the first stack.
 * 
 * */

public class Multiplex_Pixel_Colocalization_ implements PlugIn 
{
  static String title = "Multiplex Pixel Colocalization (Each image is a channel)";
  static final int validChannelMinCount = 2;
  static final int validChannelMaxCount = 8;
  static final String POSITIVE = "(+)";
  static final String NEGATIVE = "(-)";

  /*Information about channels to analyze*/
  int channelCount = 0;
  double[] channelThreshold = null;
  String[] channelTitles = null; //User Inputed titles
  ImagePlus[] inputImages = null;
  int width = 0, height = 0, stackSize = 0;

  /*Output Info*/
  String outputCSV = ""; 
  int[] colocalizationCSVCounts = null; 

  /*Region of Interest*/
  boolean analyzeROI = false;

  /*Colocalization Output Images*/
  int colocalizationImageCount = 0;
  int [][]colocalizationImageDetails = null;
  ImagePlus [] colocalizationImages = null; 
  ImagePlus outputImage;

  public void run(String arg) 
  {
    int[] openWindowList = null;
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
      IJ.showMessage("Exit on Error");
      return;
    }
 
    /* Set up arrays based on channels selected by user*/
    inputImages = new ImagePlus[channelCount];
    channelThreshold = new double[channelCount];
    channelTitles = new String[channelCount];
    //There are 2^(#images) options for overlap
    colocalizationCSVCounts = new int[(int)Math.pow(2, channelCount)]; 
    
    
    /*Get info on channels to analyze*/
    if (!getChannels(openWindowList) || !loadRoiInfo() || !selectColocalizationImages()) 
    {
      return; 
    }
    
    outputImage.setStack(colocalizationImages[0]);
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
      gd.addStringField("Channel Title: ", "");
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
      channelTitles[i] = gd.getNextString();
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
    if (stackSize > 1)
    {
      IJ.showMessage(title, "WARN: Stack size greater than 1, only first stack will be analyzed");
    }
    return true;
  }

  
  private boolean loadRoiInfo()
  {
    GenericDialog gd = new GenericDialog(title);
    String[] items = {"Yes", "No"};

    gd.addChoice("Do you have a region of interest to load?", items, items[0]);
    gd.showDialog();
    if (gd.wasCanceled()) 
    {
      return false;
    }
    analyzeROI = (gd.getNextChoiceIndex()==0);
    return true;
  }
  
  private boolean selectColocalizationImages()
  {
    GenericDialog gd = new GenericDialog(title);
    //TODO: Hack to have each one pop up... should really use scroll bar
    gd.addNumericField("How many colocalized images would you like to display?", colocalizationImageCount, 1);
    gd.showDialog();
    if (gd.wasCanceled()) 
    {
      return false;
    }
    double count = gd.getNextNumber();
    if((Double.isNaN(count))||(count != Math.ceil(count))||
       (count > Math.pow(2, channelCount))||(count<=0))
    {
      IJ.showMessage(title, "ERR: Entered an invalid number. Analysis will continue with no images displayed");
    }
    colocalizationImageCount = (int)count;

    if(colocalizationImageCount > 0)
    {
      colocalizationImageDetails = new int[colocalizationImageCount][channelCount];
      colocalizationImages = new ImagePlus[colocalizationImageCount];

      for(int i=0; i < colocalizationImageCount; i++)
      {
        //int sliceCount = 0; 
        gd.addMessage("Colocalized Image #" + Integer.toString(i));
        for(int j=0; j < channelCount; j++) 
        {
          gd.addCheckbox(channelTitles[j], false);
        }
        gd.showDialog();
        if (gd.wasCanceled()) 
        {
          return false;
        }
        for(int j=0; j < channelCount; j++) 
        {
           colocalizationImages[j] = inputImages[0].duplicate();
           if(gd.getNextBoolean() == true)
           {
             //sliceCount++;
             ImageProcessor ip = inputImages[j].getStack().getProcessor(1);
             colocalizationImageDetails[i][j] = 1; 
             colocalizationImages[j].addSlice(channelTitles[j],ip);
           } 
           else 
           {
             colocalizationImageDetails[i][j] = 0;
           }
        }
        ImageProcessor ip = inputImages[0].getChannelProcessor();
        colocalizationImages[i].addSlice("Coloq",ip);
        new ImagePlus("Colocalizated points ", colocalizationImages[i]).show();
      }
    }
    
    return true;
  }
  
  //          imageProcessor[channelCount].putPixelValue(x, y, (pixelLit[channelCount]==1)?255:0);

  /*
   * This will step through all image options
   * And tally colocalization counts for each channel combo option
   * This info is stored in colocalizationCSVCounts array.
   * if you treat the colocalizationIndex like binary, each bit
   * represents a channel. so we shift in each channel true/false 
   * to create the appropriate colocalization option 
   * */
  private void calculateColocolization()
  {
    int[] pixelLit = new int[channelCount + 1];
    ImageProcessor [] imageProcessor = new ImageProcessor[channelCount];

    for(int i=0; i<(channelCount); i++)
    {
      imageProcessor[i] = inputImages[i].getStack().getProcessor(1);
      imageProcessor[i].setCalibrationTable(inputImages[i].getCalibration().getCTable());
    }
    
    for (int x=0; x<width; x++) 
    {
      for (int y=0; y<height; y++) 
      {
        //Pixel Analysis 
        int colocalizationIndex = 0x0; //Allows selection of right count to increase.
        pixelLit[channelCount] = 1;

        for(int i=0; i<channelCount; i++)
        {
          pixelLit[i] = (imageProcessor[i].getPixel(x,y) > channelThreshold[i])?1:0;
          colocalizationIndex = (colocalizationIndex << 1) + pixelLit[i]; 
        }          
        colocalizationCSVCounts[colocalizationIndex]++;
      }   
    }   
  }
  
  
  private boolean saveColocalizationCounts()
  {
    String toPrint = "";
    int[] bitmasks = new int[channelCount];
    
    /*Create Bitmasks for each channel*/
    for(int i=0; i<channelCount; i++)
    {
      int shift = 1; 
      toPrint = toPrint + channelTitles[i] + ",";
      bitmasks[i] = shift << (channelCount - 1 - i);
    }
    toPrint = toPrint + "pixel count\n";
    
    for(int i=0; i < colocalizationCSVCounts.length; i++)
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
      toPrint = toPrint + Integer.toString(colocalizationCSVCounts[i]) + "\n";
    }
    IJ.showMessage(title, "Saving File");
    PrintWriter pw = null;
    try {
        pw = new PrintWriter(new File("/Users/axthelm/Documents/NewData.csv"));
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
