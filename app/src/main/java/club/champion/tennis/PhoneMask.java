package club.champion.tennis;

import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;

/** Russian phone mask, retaining meaningful cursor position and international numbers. */
final class PhoneMask implements TextWatcher {
    private final EditText field;
    private boolean changing;
    private String before="";
    private int start,removed,inserted,selectionStart,selectionEnd;
    private PhoneMask(EditText field){this.field=field;}
    static void attach(EditText field){
        field.setSingleLine(true);field.setHint("+7 (999) 123-45-67");
        PhoneMask mask=new PhoneMask(field);field.addTextChangedListener(mask);
        if(field.length()>0)field.setText(format(field.getText().toString()));
    }
    static String digits(String value){return value==null?"":value.replaceAll("[^0-9]","");}
    static String canonical(String value){
        String number=digits(value);if(number.isEmpty())return "";
        boolean plus=value.trim().startsWith("+");
        if(plus)return number;
        if(number.length()==10)return "7"+number;
        if(number.startsWith("8")&&number.length()<=11)return "7"+number.substring(1);
        return number.startsWith("7")||number.length()>10?number:"7"+number;
    }
    static String format(String value){
        String number=canonical(value);if(number.isEmpty())return "";
        if(!number.startsWith("7"))return "+"+number;
        String local=number.substring(1);StringBuilder result=new StringBuilder("+7");
        if(!local.isEmpty())result.append(" (").append(local,0,Math.min(3,local.length()));
        if(local.length()>=3)result.append(')');
        if(local.length()>3)result.append(' ').append(local,3,Math.min(6,local.length()));
        if(local.length()>6)result.append('-').append(local,6,Math.min(8,local.length()));
        // Keep extra digits visible so validation rejects an overlong number instead of silently changing it.
        if(local.length()>8)result.append('-').append(local.substring(8));
        return result.toString();
    }
    @Override public void beforeTextChanged(CharSequence s,int start,int count,int after){
        if(changing)return;before=s.toString();this.start=start;removed=count;inserted=after;
        selectionStart=field.getSelectionStart();selectionEnd=field.getSelectionEnd();
    }
    @Override public void onTextChanged(CharSequence s,int start,int before,int count){}
    @Override public void afterTextChanged(Editable text){
        if(changing)return;
        String raw=text.toString();int cursor=Math.max(0,Math.min(field.getSelectionStart(),raw.length()));
        // Deleting an automatically inserted separator also removes the adjacent digit.
        if(inserted==0&&removed>0&&start+removed<=before.length()&&selectionStart==selectionEnd&&digits(before.substring(start,start+removed)).isEmpty()){
            boolean backwards=start<selectionStart;int index=backwards?Math.min(start-1,raw.length()-1):Math.min(start,raw.length());
            while(index>=0&&index<raw.length()&&(raw.charAt(index)<'0'||raw.charAt(index)>'9'))index+=backwards?-1:1;
            if(index>=0&&index<raw.length()){raw=raw.substring(0,index)+raw.substring(index+1);if(index<cursor)cursor--;}
        }
        boolean atEnd=cursor>=raw.length();int preceding=digits(raw.substring(0,Math.min(cursor,raw.length()))).length();
        String canonical=canonical(raw);if(canonical.length()>digits(raw).length())preceding++;
        String formatted=format(raw);if(formatted.equals(text.toString()))return;
        int caret=formatted.length();
        if(!atEnd){caret=0;int seen=0;while(caret<formatted.length()&&seen<preceding){char c=formatted.charAt(caret++);if(c>='0'&&c<='9')seen++;}}
        changing=true;try{text.replace(0,text.length(),formatted);field.setSelection(Math.max(0,Math.min(caret,field.length())));}finally{changing=false;}
    }
}
