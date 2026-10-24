package club.champion.tennis;

import android.test.InstrumentationTestCase;
import android.text.InputType;
import android.widget.EditText;

/** Exercise real Editable/TextWatcher cursor behavior, not just a formatting function. */
public class PhoneMaskTest extends InstrumentationTestCase {
    EditText field(){EditText field=new EditText(getInstrumentation().getTargetContext());field.setInputType(InputType.TYPE_CLASS_PHONE);PhoneMask.attach(field);return field;}
    public void testPastedNumbersAndInternationalPrefix(){getInstrumentation().runOnMainSync(()->{
        EditText phone=field();
        for(String input:new String[]{"+7 (999) 123-45-67","79991234567","8 (999) 123-45-67","9991234567"}){
            phone.setText(input);assertEquals("+7 (999) 123-45-67",phone.getText().toString());assertEquals("79991234567",profile(phone).normalizedPhone());assertTrue(profile(phone).validPhone());
        }
        phone.setText("+44 7700 900123");assertEquals("+447700900123",phone.getText().toString());assertEquals("447700900123",profile(phone).normalizedPhone());assertTrue(profile(phone).validPhone());
    });}
    public void testTypingAndDeletingWholeNumber(){getInstrumentation().runOnMainSync(()->{
        EditText phone=field();phone.setText("");phone.setSelection(0);
        for(char digit:"9991234567".toCharArray()){phone.getText().insert(phone.getSelectionStart(),Character.toString(digit));assertEquals(phone.length(),phone.getSelectionStart());}
        assertEquals("+7 (999) 123-45-67",phone.getText().toString());
        for(int step=0;step<30&&phone.length()>0;step++){int cursor=phone.getSelectionStart();assertTrue(cursor>0);phone.getText().delete(cursor-1,cursor);}
        assertEquals("",phone.getText().toString());
    });}
    public void testDeletingSeparatorAndInsertingInMiddle(){getInstrumentation().runOnMainSync(()->{
        EditText phone=field();phone.setText("79991234567");phone.setSelection(13);phone.getText().delete(12,13);
        assertEquals("+7 (999) 124-56-7",phone.getText().toString());assertEquals(11,phone.getSelectionStart());assertFalse(profile(phone).validPhone());
        phone.getText().insert(phone.getSelectionStart(),"3");assertEquals("+7 (999) 123-45-67",phone.getText().toString());assertEquals(12,phone.getSelectionStart());
        phone.setSelection(12);phone.getText().delete(12,13);assertEquals("+7 (999) 123-56-7",phone.getText().toString());assertEquals(12,phone.getSelectionStart());
    });}
    public void testSelectionReplacementAndClearing(){getInstrumentation().runOnMainSync(()->{
        EditText phone=field();phone.setText("79991234567");phone.setSelection(4,7);phone.getText().replace(4,7,"912");assertEquals("+7 (912) 123-45-67",phone.getText().toString());
        phone.setSelection(0,phone.length());phone.getText().replace(0,phone.length(),"89991234567");assertEquals("+7 (999) 123-45-67",phone.getText().toString());
        phone.setSelection(0,phone.length());phone.getText().clear();assertEquals("",phone.getText().toString());assertFalse(profile(phone).validPhone());
    });}
    public void testPartialAndOverlongRussianNumbersAreNotSubmitted(){getInstrumentation().runOnMainSync(()->{
        EditText phone=field();
        for(String input:new String[]{"","+7","+7 (999)","+7 (999) 123-45-6","+7 (999) 123-45-678"}){phone.setText(input);assertFalse("Incomplete or overlong Russian number: "+input,profile(phone).validPhone());}
        assertEquals("799912345678",PhoneMask.digits(phone.getText().toString()));
        phone.setText("70000000000");assertEquals("+7 (000) 000-00-00",phone.getText().toString());assertEquals("70000000000",profile(phone).normalizedPhone());
    });}
    ClientProfile profile(EditText phone){return new ClientProfile("","",phone.getText().toString(),"");}
}
