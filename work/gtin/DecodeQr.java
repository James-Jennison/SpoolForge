import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.File;
import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
public class DecodeQr {
 public static void main(String[] a) throws Exception {
  BufferedImage im=ImageIO.read(new File(a[0])); int w=im.getWidth(),h=im.getHeight();
  int[] p=im.getRGB(0,0,w,h,null,0,w);
  BinaryBitmap b=new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(w,h,p)));
  Result r=new MultiFormatReader().decode(b,java.util.Map.of(DecodeHintType.POSSIBLE_FORMATS,java.util.List.of(BarcodeFormat.QR_CODE),DecodeHintType.TRY_HARDER,true));
  System.out.println(r.getBarcodeFormat()+" "+r.getText());
 }
}
