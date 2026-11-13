"""Templated Indian SMS set: scams (label 1) and everyday legitimate messages (label 0).

Written from public scam-awareness patterns (RBI, NPCI, cybercrime.gov.in advisories) and the shape of
real bank / delivery / OTP messages. Every row keeps its template id so train/test can be split by
template: the test set then contains wordings the model never saw.
"""
import random

BANKS = [("SBI", "SBIINB"), ("HDFC Bank", "HDFCBK"), ("ICICI Bank", "ICICIB"), ("Axis Bank", "AXISBK"),
         ("Kotak", "KOTAKB"), ("PNB", "PNBSMS"), ("Bank of Baroda", "BOBTXN"), ("Canara Bank", "CANBNK")]
NAMES = ["Rahul", "Priya", "Amit", "Sneha", "Vikram", "Anjali", "Arjun", "Kavya", "Rohan", "Meera",
         "Suresh", "Lakshmi", "Imran", "Fatima", "Deepak", "Pooja", "Karthik", "Divya"]
SHOPS = ["Amazon", "Flipkart", "Swiggy", "Zomato", "Myntra", "BigBasket", "Uber", "Ola", "IRCTC", "Jio"]
DLT_PREFIX = ["VM", "VK", "AD", "JD", "BZ", "TX", "AX", "JM", "BP", "CP"]
LURE = ["kyc", "update", "verify", "secure", "reward", "refund", "helpdesk", "support", "login", "care"]
BADTLD = ["top", "xyz", "icu", "live", "shop", "click", "in", "info", "online", "site", "cc", "co"]
FREE = ["workers.dev", "pages.dev", "vercel.app", "netlify.app", "web.app", "blogspot.com"]
SHORT = ["bit.ly", "tinyurl.com", "cutt.ly", "rb.gy", "is.gd", "shorturl.at"]


def scam_link(r, brand=""):
    b = (brand or r.choice(["sbi", "hdfc", "icici", "paytm", "phonepe", "indiapost", "irctc", "bescom", "epfo"])).lower().replace(" ", "")
    code = "".join(r.choice("abcdefghjkmnpqrstuvwxyz23456789") for _ in range(r.randint(4, 7)))
    style = r.randrange(6)
    if style == 0:
        host = f"{b}-{r.choice(LURE)}.{r.choice(BADTLD)}"
    elif style == 1:
        host = f"{b}{r.choice(LURE)}{r.randint(1, 99)}.{r.choice(BADTLD)}"
    elif style == 2:
        host = f"{b}-{r.choice(LURE)}-{r.choice(LURE)}.{r.choice(FREE)}"
    elif style == 3:
        return f"{r.choice(SHORT)}/{code}"
    elif style == 4:
        host = f"{r.randint(11, 223)}.{r.randint(0, 255)}.{r.randint(0, 255)}.{r.randint(1, 254)}"
    else:
        host = f"{r.choice(LURE)}-{b}.{r.choice(BADTLD)}"
    path = r.choice(["", "/verify", "/kyc", "/login", f"/{code}", "/update.php", f"/?id={code}"])
    return r.choice(["http://", "https://", ""]) + host + path


def mobile(r):
    n = str(r.choice([6, 7, 8, 9])) + "".join(str(r.randint(0, 9)) for _ in range(9))
    return r.choice(["+91", "", "+91 "]) + n


def dlt(r, tag):
    return f"{r.choice(DLT_PREFIX)}-{tag}" + r.choice(["", "-S", "-T"])


def fill(r, t):
    bank, tag = r.choice(BANKS)
    v = dict(
        bank=bank, tag=tag, name=r.choice(NAMES), shop=r.choice(SHOPS),
        amt=r.choice(["499", "1,999", "2500", "4,999.00", "10000", "15,000", "49,999", "1.5 lakh", "250", "75"]),
        otp="".join(str(r.randint(0, 9)) for _ in range(r.choice([4, 6]))),
        acct="XX" + "".join(str(r.randint(0, 9)) for _ in range(4)),
        date=r.choice(["13-11-26", "14/11/2026", "15-Nov-26", "today", "16 Nov"]),
        time=r.choice(["10:15", "14:32", "21:05", "08:47"]),
        num=mobile(r).replace(" ", ""),
        link=scam_link(r, bank.split()[0]),
        anylink=scam_link(r),
        ref="".join(str(r.randint(0, 9)) for _ in range(12)),
        awb="".join(str(r.randint(0, 9)) for _ in range(10)),
    )
    return t.format(**v), v

# (template id, sender kind, text). sender kind: mobile | fake | dlt:<TAG> | dltbank | none
SCAMS = [
    # KYC / account block
    ("kyc1", "mobile", "Dear customer your {bank} account will be blocked today. Update KYC immediately at {link}"),
    ("kyc2", "mobile", "{bank}: Your PAN card is not linked. Account will be suspended within 24 hrs. Click {link} to update"),
    ("kyc3", "fake", "Dear user your YONO account has been deactivated due to incomplete KYC. Re-activate now {link}"),
    ("kyc4", "mobile", "Aapka {bank} account aaj band ho jayega. KYC update karne ke liye turant click karein {link}"),
    ("kyc5", "mobile", "प्रिय ग्राहक, आपका {bank} खाता आज ब्लॉक हो जाएगा। तुरंत KYC अपडेट करें: {link}"),
    ("kyc6", "mobile", "Your {bank} netbanking will expire today. Please verify your Aadhaar and PAN at {link} to avoid suspension"),
    # electricity
    ("elec1", "mobile", "Dear Consumer, your electricity power will be disconnected tonight at 9:30 pm because your previous month bill was not updated. Please immediately contact our electricity officer {num}"),
    ("elec2", "mobile", "BESCOM: Your power connection will be cut today. Last bill not updated. Call {num} immediately"),
    ("elec3", "mobile", "Pyare upbhokta, aaj raat 9 baje aapki bijli kaat di jayegi. Bill update ke liye turant {num} par call karein"),
    ("elec4", "mobile", "प्रिय उपभोक्ता, आज रात आपकी बिजली काट दी जाएगी। बिल अपडेट नहीं है। तुरंत {num} पर कॉल करें"),
    # courier / customs
    ("cour1", "mobile", "India Post: Your package is held at the warehouse due to incomplete address. Update within 24 hours {link}"),
    ("cour2", "fake", "FedEx: A parcel in your name contains illegal items. Customs case registered. Press 1 or call {num} immediately"),
    ("cour3", "mobile", "Your shipment {awb} could not be delivered. Pay Rs 25 redelivery fee at {anylink}"),
    ("cour4", "mobile", "Aapka parcel customs par ruka hai. Rs {amt} clearance fee pay karein: {anylink}"),
    # digital arrest / authority
    ("arr1", "mobile", "This is CBI cyber cell. Your Aadhaar is linked to a money laundering case. Do not disconnect. Call {num} now or face arrest"),
    ("arr2", "mobile", "Mumbai Police Narcotics: a parcel with drugs was booked on your Aadhaar. Join video call on Skype immediately {num} to avoid arrest"),
    ("arr3", "mobile", "TRAI: Your mobile number will be blocked in 2 hours due to illegal activity. Press 9 or call {num}"),
    ("arr4", "mobile", "आपके आधार पर मनी लॉन्ड्रिंग केस दर्ज है। गिरफ्तारी से बचने के लिए तुरंत {num} पर कॉल करें"),
    # job / task
    ("job1", "mobile", "Hi! Part time job, work from home, daily income Rs 3000-8000. Just like YouTube videos. Contact on Telegram: {anylink}"),
    ("job2", "mobile", "Congratulations {name}, you are selected for Amazon part-time job. Earn Rs {amt} per day. WhatsApp {num}"),
    ("job3", "mobile", "Ghar baithe kamaye Rs {amt} roz. Simple task, rating dene ka kaam. Abhi join karein {anylink}"),
    ("job4", "mobile", "घर बैठे रोज़ ₹{amt} कमाएं। सिर्फ वीडियो लाइक करें। टेलीग्राम पर जुड़ें {anylink}"),
    # lottery / prize / cashback
    ("lot1", "mobile", "Congratulations! Your number has won Rs 25,00,000 in KBC lottery. To claim call {num} and pay processing fee"),
    ("lot2", "fake", "You have won a cashback of Rs {amt} from PhonePe. Claim before it expires: {anylink}"),
    ("lot3", "mobile", "Dear {name}, you are the lucky draw winner of a Tata Safari. Pay Rs 12,500 registration to receive. Call {num}"),
    ("lot4", "mobile", "बधाई हो! आपने ₹25 लाख की लॉटरी जीती है। इनाम पाने के लिए {num} पर संपर्क करें"),
    # UPI collect / refund / OTP theft
    ("upi1", "mobile", "You have received Rs {amt} refund. Enter your UPI PIN to receive the money in your account. {anylink}"),
    ("upi2", "mobile", "Sir I sent Rs {amt} to your number by mistake please return it. I am sending a collect request, please approve"),
    ("upi3", "mobile", "Your {bank} reward points worth Rs {amt} expire today. Redeem now {link}"),
    ("upi4", "mobile", "Dear customer, share the OTP sent to your number with our executive to stop the unauthorised transaction of Rs {amt}"),
    ("upi5", "mobile", "{bank} credit card limit increased. To activate share the OTP and CVV with officer at {num}"),
    # loans
    ("loan1", "mobile", "Pre-approved instant loan of Rs {amt} without documents. Get money in 5 minutes. Download app: {anylink}"),
    ("loan2", "mobile", "Bina CIBIL check ke Rs 50,000 ka loan turant. Sirf Rs 999 processing fee. Apply {anylink}"),
    # tax / gov
    ("tax1", "fake", "Income Tax Department: You are eligible for a refund of Rs {amt}. Submit your bank details at {anylink}"),
    ("tax2", "mobile", "Your EPFO PF amount Rs {amt} is ready for withdrawal. Verify Aadhaar at {link} today"),
    ("tax3", "mobile", "Your traffic challan of Rs 500 is pending. Pay now to avoid court case: {anylink}"),
    # family / friend in trouble
    ("fam1", "mobile", "Hi Mom this is my new number, my phone broke. I need Rs {amt} urgently, please send to this UPI. Don't tell dad"),
    ("fam2", "mobile", "Papa mera phone kho gaya, ye naya number hai. Jaldi Rs {amt} bhej do, emergency hai"),
    # scams from look-alike business sender IDs
    ("dltf1", "fake", "Your {bank} account KYC has expired. Avoid suspension, update now: {link}"),
    ("dltf2", "fake", "Dear customer Rs {amt} reward credited to your wallet. Redeem in 24 hrs at {anylink}"),
    ("dltf3", "fake", "Electricity bill payment failed. Connection will be disconnected tonight. Update at {anylink}"),
    ("dltf4", "fake", "Your SIM will be deactivated today due to pending e-KYC. Complete verification {anylink}"),
    # investment / crypto
    ("inv1", "mobile", "Join our VIP stock tips group, guaranteed 300% returns in 7 days. Limited seats: {anylink}"),
    ("inv2", "mobile", "Invest Rs {amt} today and get double in 15 days. Trusted crypto platform, SEBI approved. WhatsApp {num}"),
    ("inv3", "mobile", "शेयर बाजार में रोज़ 20% मुनाफा। हमारे VIP ग्रुप से जुड़ें {anylink}"),
    # sextortion / account takeover
    ("ato1", "mobile", "Your WhatsApp will be banned in 2 hours. Send the 6 digit code you received to this number to verify"),
    ("ato2", "mobile", "Hello, I sent you a code by mistake on WhatsApp, please forward it to me, urgent"),
]

HAM = [
    # OTP (legit: DLT sender, "do not share")
    ("otp1", "dltbank", "{otp} is your OTP for transaction of Rs {amt} at {shop}. Valid for 10 mins. Do not share with anyone. -{bank}"),
    ("otp2", "dlt:AMAZON", "{otp} is your Amazon OTP. Do not share it with anyone."),
    ("otp3", "dltbank", "OTP for login to {bank} NetBanking is {otp}. Never share your OTP with anyone. Bank never asks for OTP."),
    ("otp4", "dlt:SWIGGY", "Your Swiggy verification code is {otp}. Don't share this code with anyone."),
    ("otp5", "dltbank", "{otp} आपका OTP है। इसे किसी के साथ साझा न करें। -{bank}"),
    ("otp6", "dlt:UIDAIS", "{otp} is the OTP for Aadhaar ending {acct}. Valid for 10 min. Do not share. - UIDAI"),
    # transaction alerts
    ("txn1", "dltbank", "Rs {amt} debited from A/c {acct} on {date} to VPA merchant@ybl. UPI Ref {ref}. Not you? Call 18002586161 -{bank}"),
    ("txn2", "dltbank", "A/c {acct} credited with Rs {amt} on {date} by NEFT. Avl Bal Rs 23,456.78 -{bank}"),
    ("txn3", "dltbank", "Your {bank} Credit Card ending {acct} has been used for Rs {amt} at {shop} on {date} {time}."),
    ("txn4", "dlt:PAYTMB", "Paid Rs {amt} to {shop} from Paytm UPI. Ref {ref}."),
    ("txn5", "dltbank", "Salary of Rs {amt} credited to your A/c {acct} on {date}. -{bank}"),
    ("txn6", "dltbank", "Dear Customer, your EMI of Rs {amt} for loan {acct} is due on {date}. Please keep sufficient balance. -{bank}"),
    ("txn7", "dltbank", "आपके खाते {acct} से Rs {amt} डेबिट हुए। UPI Ref {ref}। -{bank}"),
    # delivery / orders
    ("del1", "dlt:AMAZON", "Your Amazon order with {shop} items will be delivered today by 9 PM. Track: amazon.in/your-orders"),
    ("del2", "dlt:FLPKRT", "Your Flipkart order has been shipped and will arrive by {date}. Track at flipkart.com"),
    ("del3", "dlt:DLVERY", "Shipment {awb} is out for delivery. Delivery agent Ramesh will call you. OTP for delivery {otp}"),
    ("del4", "dlt:SWIGGY", "Your Swiggy order is on the way! Delivery partner {name} will reach in 25 mins."),
    ("del5", "dlt:INPOST", "India Post: Article {awb} booked on {date} has been delivered. Thank you."),
    # utilities / telecom / travel
    ("util1", "dlt:BESCOM", "BESCOM: Bill for RR no {acct} for Rs {amt} generated. Due date {date}. Pay via BESCOM app or bescom.co.in"),
    ("util2", "dlt:JIOINF", "Your Jio plan expires on {date}. Recharge with Rs 299 on MyJio app to continue enjoying services."),
    ("util3", "dlt:IRCTCI", "PNR {awb} Train 12627 {date} Coach S5 Berth 32 confirmed. Happy journey - IRCTC"),
    ("util4", "dlt:AIRTEL", "Recharge of Rs 349 successful for {num}. Valid 28 days. -Airtel"),
    ("util5", "dlt:APOLLO", "Dear {name}, your appointment with Dr. Sharma is confirmed for {date} at {time}. -Apollo Clinic"),
    # personal (mobile senders, Hinglish/Hindi/English)
    ("per1", "mobile", "Hey {name}, are we still meeting at 6 today? Let me know"),
    ("per2", "mobile", "Bhai kal ka plan cancel, office me late ho jayega. Sunday ko milte hain"),
    ("per3", "mobile", "Mummy ne bola ghar aate waqt doodh aur bread le aana"),
    ("per4", "mobile", "I sent you Rs {amt} for the dinner yesterday, check your GPay"),
    ("per5", "mobile", "Call me when you are free, need to discuss the rent payment"),
    ("per6", "mobile", "कल शाम को घर पर पूजा है, समय से आ जाना"),
    ("per7", "mobile", "Happy birthday {name}! Have a great year ahead"),
    ("per8", "mobile", "Reached office. Will call you at lunch. Did you pay the electricity bill?"),
    ("per9", "mobile", "Your OTP is for the gate pass, just tell the guard {otp}. Coming down in 5 min"),
    # service messages that look scary but are real
    ("svc1", "dltbank", "Your {bank} debit card ending {acct} is blocked as requested. For a new card visit your branch. -{bank}"),
    ("svc2", "dltbank", "Dear Customer, update your KYC by visiting your nearest {bank} branch with valid ID. Ignore if already done."),
    ("svc3", "dlt:CYBDST", "Cyber fraud? Dial 1930 or report at cybercrime.gov.in. Never share OTP, PIN or CVV with anyone. - I4C"),
    ("svc4", "dlt:EPFOHO", "Dear member, contribution of Rs {amt} received in your PF account for Oct-26. -EPFO"),
    # bills, recharges, subscriptions
    ("bill1", "dlt:TNEBLT", "TNEB: Bill amount Rs {amt} for SC No {acct} due on {date}. Pay at tnebltd.gov.in or any authorised centre."),
    ("bill2", "dlt:AIRTEL", "Your Airtel postpaid bill of Rs {amt} is due on {date}. Pay on Airtel Thanks app to avoid late fee."),
    ("bill3", "dlt:JIOINF", "Rs 239 recharge successful. Plan valid till {date}. Data balance 1.5 GB/day. -Jio"),
    ("bill4", "dlt:NFLIX", "Your Netflix membership has been renewed for Rs 199. Thanks for watching."),
    ("bill5", "dlt:GASBKG", "Your LPG cylinder booking {ref} is confirmed. Delivery expected by {date}. Amount Rs 903."),
    ("bill6", "dlt:FASTAG", "FASTag: Rs 85 deducted at Toll Plaza on {date} {time}. Avl bal Rs {amt}."),
    # insurance, investments, government
    ("ins1", "dlt:LICIND", "Dear policyholder, premium of Rs {amt} for policy {acct} is due on {date}. Pay via LIC app or branch."),
    ("ins2", "dlt:ZERODH", "Your order to buy 10 shares of INFY at Rs 1,520 has been executed. -Zerodha"),
    ("ins3", "dlt:CDSLIN", "Debit in demat a/c {acct}: 5 units on {date}. If not done by you, contact your DP. -CDSL"),
    ("gov1", "dlt:DIGILK", "Your Aadhaar card has been issued to your DigiLocker account. Access it anytime in the DigiLocker app."),
    ("gov2", "dlt:ITDEPT", "Your ITR for AY 2026-27 has been processed. Refund of Rs {amt} will be credited to your pre-validated bank account."),
    ("gov3", "dlt:UIDAIS", "Your Aadhaar update request URN {ref} has been completed successfully. -UIDAI"),
    # legit promotions from registered senders
    ("promo1", "dlt:MYNTRA", "End of Season Sale is LIVE! Flat 50-80% off on top brands. Shop now on the Myntra app. T&C apply"),
    ("promo2", "dlt:SWIGGY", "Craving biryani? Get 40% off up to Rs 80 on your next order. Use code TRYNEW. Order on Swiggy app"),
    ("promo3", "dlt:BIGBSK", "Fresh fruits and vegetables at up to 30% off this weekend only. Order on BigBasket"),
    # personal, more
    ("per10", "mobile", "Bro send me the notes of yesterday's class, exam is tomorrow"),
    ("per11", "mobile", "Didi train 2 ghante late hai, station pe mat aana abhi"),
    ("per12", "mobile", "Paid the school fees today, receipt is with me. Remind me to keep it safe"),
]


def generate(per_template=40, seed=7):
    r = random.Random(seed)
    rows = []
    for label, table in ((1, SCAMS), (0, HAM)):
        for tid, skind, t in table:
            for _ in range(per_template):
                text, v = fill(r, t)
                if skind == "mobile":
                    sender = mobile(r)
                elif skind == "fake":  # look-alike header, or plain mobile number
                    sender = r.choice([f"{r.choice(DLT_PREFIX)}-{r.choice(['SBIUPD', 'KYCSBI', 'REWARD', 'INFOSM', 'ALERTS'])}", mobile(r)])
                elif skind == "dltbank":
                    sender = dlt(r, v["tag"])
                elif skind.startswith("dlt:"):
                    sender = dlt(r, skind[4:])
                else:
                    sender = ""
                if r.random() < 0.1:  # sender not always available (shared text, notification preview)
                    sender = ""
                rows.append(dict(text=text, sender=sender, label=label, template=tid, source="indian"))
    return rows


if __name__ == "__main__":
    rows = generate()
    assert len({r["template"] for r in rows}) == len(SCAMS) + len(HAM)
    assert all(r["text"] and "{" not in r["text"] for r in rows), "unfilled slot"
    print(len(rows), "rows;", sum(r["label"] for r in rows), "scam;", len(SCAMS), "scam templates;", len(HAM), "ham templates")
    for r in rows[::400]:
        print(r["label"], r["sender"], "|", r["text"][:110])
