
import ij.*;
import ij.plugin.*;
import ij.gui.*;
import ij.process.*;



public class Multiplex_Pixel_Colocalization_ implements PlugIn 
{
  static String title = "Multiplex Pixel Colocalization";
  static final int SCALE = 0;
  static final int validChannelMinCount = 2;
  static final int validChannelMaxCount = 6;
  static final String channelColor [] = {"Red", "Green", "Blue", "Yellow", "Orange"};


  double[] channelThreshold;
  
  ImagePlus[] inputImages;
  ImagePlus outputImage;
  ImageStack outputImageStack; 


  int width = 0, height = 0, stackSize = 0; 
  static int  R=0;
  static int  G=1;
  static int  B=2;
  static int  g=3;

  public void run(String arg) 
  {
    int[] openWindowList = null; //List of all open windows
    int channelCount = 0; //# of channels user wants to analyze
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
    
    
    /*Get info on channels to analyze*/
    if (!getChannels(openWindowList, channelCount)) 
    {
      return; 
    }
    outputImage = inputImages[0].duplicate();

    calculateColocolization(channelCount);
    outputImage.show();
  }
  
  /*
   * This function is sent the openWindowList and creates 
   * a list of strings to return using only image names.
   * */
  public String[] getImageOptions(int[] openWindowList)
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
  public int returnChannelCount(int imagesOpen)
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
      IJ.showMessage(title, "ERR: Please enter a valid integer between " + Integer.toString(validChannelMinCount) 
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
  
  public boolean getChannels(int[] openWindowList, int channelCount) 
  {
    String[] imagetitleoptions = getImageOptions(openWindowList);
    GenericDialog gd = new GenericDialog(title);
    for(int i = 0; i < channelCount; i++) 
    {
      String option = "Channel #" + Integer.toString(i) + "(" + channelColor[i] +"): ";
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

  public void calculateColocolization(int channelCount)
  {
    boolean[] pixelLit = new boolean[channelCount + 1];
    ImageProcessor [] imageProcessor = new ImageProcessor[channelCount + 1];
    ImageStack[] stack = new ImageStack[channelCount + 1];
    float[][] ctable = new float[channelCount + 1][];
 
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
      for(int i=0; i<(channelCount+1); i++)
      {
        imageProcessor[i] = stack[i].getProcessor(n);
        imageProcessor[i].setCalibrationTable(ctable[i]);
      }

      for (int x=0; x<width; x++) 
      {
        for (int y=0; y<height; y++) 
        {
          pixelLit[channelCount] = true;
          for(int i=0; i<channelCount; i++)
          {
            pixelLit[i] = imageProcessor[i].getPixel(x,y) > channelThreshold[i];
            pixelLit[channelCount] = pixelLit[i] & pixelLit[channelCount];
          }          
          imageProcessor[channelCount].putPixelValue(x, y, pixelLit[channelCount]?255:0);
        }   
      }   
      IJ.showProgress((double)n/stackSize);
      IJ.showStatus(n+"/"+stackSize);
    }
  }

}
